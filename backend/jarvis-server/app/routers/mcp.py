import json
from typing import Any, Optional, Union

from fastapi import APIRouter, Depends, Header
from fastapi.responses import JSONResponse
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from .. import mcp_gateway
from ..auth import require_api_key
from ..db import get_conn
from ..mcp_tools import call_tool, get_tool, list_tools


router = APIRouter(
    prefix="/mcp",
    tags=["mcp"],
    dependencies=[Depends(require_api_key)],
)

# 서버 자체 도구 중 앱에 내보내는 것. meeting_search 는 앱의 search_meetings 와 겹치고,
# notion_search 는 외부 노션 MCP 로 대신한다. agent_graph 는 list_tools() 를 그대로 쓴다.
_APP_NATIVE = {"calendar_list_events", "slack_post_message"}
_NATIVE_TITLES = {
    "calendar_list_events": ("gcal_list_events", "구글 캘린더 일정 조회",
                             "사용자가 구글 캘린더 일정을 물을 때 기간 안의 일정을 조회합니다. time_min, time_max 는 한국 시간 ISO 8601 형식입니다."),
    "slack_post_message": ("slack_post_message", "슬랙 메시지 보내기",
                           "슬랙 기본 채널에 메시지를 보냅니다. 슬랙 MCP 연결이 없을 때 씁니다. 앱이 실행 전에 사용자 확인을 받습니다."),
}
_PROVIDER = {"calendar_list_events": "google", "slack_post_message": "slack"}
_ALIASES = {exported: native for native, (exported, _, _) in _NATIVE_TITLES.items()}


class JsonRpcRequest(BaseModel):
    jsonrpc: str = "2.0"
    id: Optional[Union[int, str]] = None
    method: str
    params: dict[str, Any] = Field(default_factory=dict)


def connected_providers(user_id: str = "default") -> set[str]:
    try:
        with get_conn() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT provider FROM user_integrations WHERE user_id = %s", (user_id,))
                return {row["provider"] for row in cur.fetchall()}
    except Exception:
        return set()


def app_tools() -> list[dict[str, Any]]:
    """앱이 Gemini 에 넘길 도구 목록: 연결된 외부 MCP 도구 + 계정이 연결된 서버 자체 도구."""
    gw = mcp_gateway.gateway
    tools = gw.manifest() if gw else []
    taken = {t["name"] for t in tools}
    connected = connected_providers()
    for manifest in list_tools():
        native = manifest["name"]
        if native not in _APP_NATIVE or _PROVIDER[native] not in connected:
            continue
        exported, title, description = _NATIVE_TITLES[native]
        if exported in taken or (native == "slack_post_message" and "slack_send_message" in taken):
            continue
        params = mcp_gateway.sanitize_schema(manifest["inputSchema"])
        confirm = bool(manifest["annotations"].get("requires_confirmation"))
        tools.append({
            "name": exported, "title": title, "description": description, "inputSchema": params,
            "annotations": {"requires_confirmation": confirm, "source": "jarvis"},
            "gemini": {"name": exported, "description": description, "parameters": params},
        })
    return tools


@router.get("/tools")
def get_tools():
    return {"tools": app_tools()}


@router.get("/status")
def status():
    gw = mcp_gateway.gateway
    return {"servers": gw.status() if gw else {}}


# 외부 MCP 연결은 서버 하나에 계정 하나(.env 키 또는 "default" 사용자의 계정 연결)로 연다.
# 다른 user_id 로 외부 도구를 부르면 남의 계정으로 실행되지 않게 거절한다.
_GATEWAY_USER = "default"


def _with_details(result: dict[str, Any]) -> dict[str, Any]:
    """서버 자체 도구는 본문이 "1개의 일정을 찾았습니다" 뿐이라, 세부 내용(structured_content)을 글에 붙인다."""
    details = result.get("structured_content")
    if result.get("is_error") or not details:
        return result
    text = (result.get("content") or "") + "\n" + json.dumps(details, ensure_ascii=False, default=str)
    return {**result, "content": text[:mcp_gateway.RESULT_LIMIT]}


def _requires_confirmation(name: str) -> bool:
    gw = mcp_gateway.gateway
    if gw and gw.is_external(name):
        return gw.tools[name].confirm
    tool = get_tool(_ALIASES.get(name, name))
    return bool(tool and tool.requires_confirmation)


_UNCERTAIN_RETRY = ("이전 시도에서 이미 실행됐을 수 있어 다시 실행하지 않았습니다. "
                    "다시 보내지 말고, 사용자에게 직접 확인해 달라고 안내하세요.")


def _own_connection():
    import psycopg
    from psycopg.rows import dict_row

    from ..config import settings
    return psycopg.connect(settings.database_url, connect_timeout=10, row_factory=dict_row)


def _claim_dispatch(key: str, name: str) -> bool:
    """쓰기 시작을 따로 커밋해 남긴다. 이미 시작 기록이 있으면 False(앞 시도가 실행됐을 수 있음)."""
    # 요청 트랜잭션과 따로 바로 커밋해야 서버가 꺼져도 남는다. 요청들이 공용 풀을 다 쥐고 있을 수 있어
    # 풀이 아닌 별도 연결을 잠깐 연다(쓰기 확인을 거친 요청만 오므로 드물다).
    with _own_connection() as conn:
        row = conn.execute(
            """
            INSERT INTO external_dispatches (request_key, tool) VALUES (%s, %s)
            ON CONFLICT (request_key) DO UPDATE SET status = 'started', updated_at = now()
                WHERE external_dispatches.status = 'failed'
            RETURNING request_key
            """,
            (key, name),
        ).fetchone()
        conn.commit()
    return row is not None


def _finish_dispatch(key: str, definite_failure: bool) -> None:
    with _own_connection() as conn:
        conn.execute("UPDATE external_dispatches SET status = %s, updated_at = now() WHERE request_key = %s",
                     ("failed" if definite_failure else "done", key))
        conn.commit()


async def _run(name: str, arguments: dict[str, Any], user_id: str) -> dict[str, Any]:
    gw = mcp_gateway.gateway
    native = _ALIASES.get(name, name)
    if gw and gw.is_external(name):
        if user_id != _GATEWAY_USER:
            return {"is_error": True, "error": "외부 도구는 기본 사용자 계정으로만 실행합니다."}
        # 노션 MCP 가 아직 안 붙었으면, 예전 계정 연결로 동작하던 서버 자체 노션 검색을 쓴다.
        if not gw.is_ready(name) and get_tool(native) is not None:
            return _with_details(await run_in_threadpool(call_tool, native, arguments, user_id))
        return await gw.call(name, arguments)
    if get_tool(native) is None:
        return {"is_error": True, "error": f"unknown tool: {name}"}
    return _with_details(await run_in_threadpool(call_tool, native, arguments, user_id))


async def _call(name: str, arguments: dict[str, Any], user_id: str, request_key: str = "") -> dict[str, Any]:
    if not (request_key and _requires_confirmation(name)):
        return await _run(name, arguments, user_id)
    if not await run_in_threadpool(_claim_dispatch, request_key, name):
        return {"is_error": True, "uncertain": True, "error": _UNCERTAIN_RETRY}
    result = await _run(name, arguments, user_id)
    definite_failure = bool(result.get("is_error")) and not result.get("uncertain")
    await run_in_threadpool(_finish_dispatch, request_key, definite_failure)
    return result


@router.post("")
async def jsonrpc(body: JsonRpcRequest, idempotency_key: str = Header("", alias="Idempotency-Key")):
    if body.method == "tools/list":
        return {"jsonrpc": "2.0", "id": body.id, "result": {"tools": await run_in_threadpool(app_tools)}}
    if body.method == "tools/call":
        name = body.params.get("name", "")
        arguments = body.params.get("arguments") or {}
        user_id = body.params.get("user_id") or "default"
        result = await _call(name, arguments, user_id, idempotency_key)
        if result.get("is_error") and result.get("error", "").startswith("unknown tool"):
            return JSONResponse(status_code=404, content={"jsonrpc": "2.0", "id": body.id,
                                                          "error": {"code": -32602, "message": result["error"]}})
        if result.get("is_error") and not result.get("uncertain"):
            # 실패는 2xx 가 아니게 돌려준다. 앱이 끊긴 요청을 나중에 복구할 때 HTTP 코드만 보고 완료로 기록하지 않게.
            # (같은 키의 실패 응답은 저장하지 않으므로, 실패한 쓰기는 다시 시도할 수 있다.)
            return JSONResponse(status_code=422, content={"jsonrpc": "2.0", "id": body.id, "error": {
                "code": -32000, "message": result.get("error") or result.get("content") or "도구 실행 실패"}})
        return {
            "jsonrpc": "2.0",
            "id": body.id,
            "result": {
                "content": [{"type": "text", "text": result.get("content") or result.get("error") or ""}],
                "structuredContent": result.get("structured_content", result),
                "isError": bool(result.get("is_error")),
            },
        }
    return {"jsonrpc": "2.0", "id": body.id, "error": {"code": -32601, "message": f"unknown method: {body.method}"}}
