from typing import Any, Optional, Union

from fastapi import APIRouter, Depends
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


async def _call(name: str, arguments: dict[str, Any], user_id: str) -> dict[str, Any]:
    gw = mcp_gateway.gateway
    if gw and gw.is_external(name):
        return await gw.call(name, arguments)
    native = _ALIASES.get(name, name)
    if get_tool(native) is None:
        return {"is_error": True, "error": f"unknown tool: {name}"}
    return await run_in_threadpool(call_tool, native, arguments, user_id)


@router.post("")
async def jsonrpc(body: JsonRpcRequest):
    if body.method == "tools/list":
        return {"jsonrpc": "2.0", "id": body.id, "result": {"tools": await run_in_threadpool(app_tools)}}
    if body.method == "tools/call":
        name = body.params.get("name", "")
        arguments = body.params.get("arguments") or {}
        user_id = body.params.get("user_id") or "default"
        result = await _call(name, arguments, user_id)
        if result.get("is_error") and result.get("error", "").startswith("unknown tool"):
            return {"jsonrpc": "2.0", "id": body.id, "error": {"code": -32602, "message": result["error"]}}
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
