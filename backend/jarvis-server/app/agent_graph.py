"""LangGraph orchestrator for Jarvis agent queries.

This replaces the deterministic keyword router (`agent_service.plan_tool_call`)
with an explicit LangGraph state machine:

    normalize -> route (LLM) -> {execute | confirm | unsupported} -> END

The router node uses an LLM (settings.jarvis_router_model) to pick one of the
registered MCP tools and build its arguments. Everything downstream (MCP tool
execution, confirmation gate, answer synthesis, run/tool-call logging) reuses
the existing helpers in `agent_service` and `mcp_tools`, so tools and DB schema
are untouched.

Enabled only when JARVIS_USE_LANGGRAPH=true and an OpenAI key is configured;
otherwise `agent_service.run_agent_query` keeps using the deterministic path.
"""

import json
from datetime import datetime
from typing import Any, Optional, TypedDict
from zoneinfo import ZoneInfo

from openai import OpenAI
from langgraph.graph import END, START, StateGraph

from .config import settings
from .mcp_tools import call_tool, create_pending_action, get_tool, list_tools


class JarvisState(TypedDict, total=False):
    # inputs
    user_id: str
    text: str
    image_id: Optional[str]
    timezone: str
    client_context: dict[str, Any]
    # working state
    run_id: str
    intent: Optional[str]
    tool_name: str
    arguments: dict[str, Any]
    tool_result: Optional[dict[str, Any]]
    pending_action: Optional[dict[str, Any]]
    final_answer: Optional[str]
    status: str


# --- LLM router -------------------------------------------------------------

_ROUTER_SYSTEM = (
    "당신은 Jarvis 개인 업무 에이전트의 의도 라우터입니다.\n"
    "사용자의 한국어 요청을 분석해 아래 도구 목록 중 정확히 하나를 선택하고 "
    "해당 도구의 inputSchema에 맞는 arguments를 만듭니다.\n"
    "적절한 도구가 없으면 tool_name을 빈 문자열(\"\")로 둡니다.\n"
    "현재 시각은 {now} (timezone: {tz}) 입니다. 캘린더 조회처럼 기간이 필요한 경우 "
    "time_min/time_max를 이 시각 기준 RFC3339 문자열로 계산하세요.\n"
    "반드시 아래 JSON 형식으로만 응답하세요:\n"
    '{{"intent": "<간단한 의도명>", "tool_name": "<도구명 또는 빈 문자열>", '
    '"arguments": {{ ... }}}}\n\n'
    "사용 가능한 도구 목록(JSON):\n{tools}"
)


def _route_with_llm(text: str, timezone: str) -> dict[str, Any]:
    """Ask the router model to choose a tool + arguments. Returns a plan dict."""
    now_iso = datetime.now(ZoneInfo(timezone)).isoformat()
    tools_manifest = json.dumps(list_tools(), ensure_ascii=False)
    client = OpenAI(api_key=settings.openai_api_key)
    response = client.chat.completions.create(
        model=settings.jarvis_router_model,
        response_format={"type": "json_object"},
        messages=[
            {
                "role": "system",
                "content": _ROUTER_SYSTEM.format(
                    now=now_iso, tz=timezone, tools=tools_manifest
                ),
            },
            {"role": "user", "content": text},
        ],
    )
    raw = response.choices[0].message.content or "{}"
    plan = json.loads(raw)
    tool_name = plan.get("tool_name") or ""
    arguments = plan.get("arguments")
    if not isinstance(arguments, dict):
        arguments = {}
    return {
        "intent": plan.get("intent") or ("unsupported" if not tool_name else tool_name),
        "tool_name": tool_name,
        "arguments": arguments,
    }


# --- Graph nodes ------------------------------------------------------------


def _node_normalize(state: JarvisState) -> dict[str, Any]:
    # Import here to avoid a circular import at module load time.
    from .agent_service import create_agent_run

    timezone = state.get("timezone") or "Asia/Seoul"
    run_id = create_agent_run(
        state["user_id"],
        state["text"],
        {
            "timezone": timezone,
            "image_id": state.get("image_id"),
            "client_context": state.get("client_context") or {},
            "orchestrator": "langgraph",
        },
    )
    return {"run_id": run_id, "timezone": timezone}


def _node_route(state: JarvisState) -> dict[str, Any]:
    from .agent_service import plan_tool_call

    try:
        plan = _route_with_llm(state["text"], state["timezone"])
        # Guard against hallucinated tool names.
        if plan["tool_name"] and get_tool(plan["tool_name"]) is None:
            plan = plan_tool_call(text=state["text"], timezone=state["timezone"])
    except Exception:
        # Any LLM/parse failure falls back to the deterministic keyword router.
        plan = plan_tool_call(text=state["text"], timezone=state["timezone"])
    return {
        "intent": plan["intent"],
        "tool_name": plan["tool_name"],
        "arguments": plan["arguments"],
    }


def _decide(state: JarvisState) -> str:
    tool = get_tool(state.get("tool_name") or "")
    if tool is None:
        return "unsupported"
    return "confirm" if tool.requires_confirmation else "execute"


def _node_unsupported(state: JarvisState) -> dict[str, Any]:
    from .agent_service import update_agent_run

    answer = "아직 처리할 수 없는 요청입니다. 일정, 회의록, 슬랙, 노션 검색부터 지원합니다."
    update_agent_run(
        state["run_id"], intent=state["intent"], status="unsupported", final_answer=answer
    )
    return {"status": "unsupported", "final_answer": answer}


def _node_confirm(state: JarvisState) -> dict[str, Any]:
    from .agent_service import build_pending_preview, update_agent_run

    preview = build_pending_preview(state["tool_name"], state["arguments"])
    pending_action_id = create_pending_action(
        user_id=state["user_id"],
        action_type=state["tool_name"],
        payload=state["arguments"],
        preview_text=preview,
    )
    update_agent_run(
        state["run_id"],
        intent=state["intent"],
        status="pending_confirmation",
        final_answer=preview,
        metadata={"pending_action_id": pending_action_id},
    )
    return {
        "status": "pending_confirmation",
        "final_answer": preview,
        "pending_action": {
            "id": pending_action_id,
            "type": state["tool_name"],
            "preview_text": preview,
            "payload": state["arguments"],
        },
    }


def _node_execute(state: JarvisState) -> dict[str, Any]:
    from .agent_service import log_tool_call, synthesize_answer, update_agent_run

    result = call_tool(state["tool_name"], state["arguments"], user_id=state["user_id"])
    log_tool_call(
        state["run_id"],
        state["tool_name"],
        state["arguments"],
        result,
        "failed" if result.get("is_error") else "completed",
    )
    plan = {"tool_name": state["tool_name"], "intent": state["intent"]}
    answer = synthesize_answer(state["text"], plan, result)
    status = "failed" if result.get("is_error") else "completed"
    update_agent_run(state["run_id"], intent=state["intent"], status=status, final_answer=answer)
    return {"status": status, "final_answer": answer, "tool_result": result}


def _build_graph():
    builder = StateGraph(JarvisState)
    builder.add_node("normalize", _node_normalize)
    builder.add_node("route", _node_route)
    builder.add_node("execute", _node_execute)
    builder.add_node("confirm", _node_confirm)
    builder.add_node("unsupported", _node_unsupported)

    builder.add_edge(START, "normalize")
    builder.add_edge("normalize", "route")
    builder.add_conditional_edges(
        "route",
        _decide,
        {"execute": "execute", "confirm": "confirm", "unsupported": "unsupported"},
    )
    builder.add_edge("execute", END)
    builder.add_edge("confirm", END)
    builder.add_edge("unsupported", END)
    return builder.compile()


GRAPH = _build_graph()


def run_graph(
    *,
    user_id: str,
    text: str,
    timezone: str = "Asia/Seoul",
    image_id: Optional[str] = None,
    client_context: Optional[dict[str, Any]] = None,
) -> dict[str, Any]:
    """Entry point mirroring agent_service.run_agent_query's response shape."""
    final = GRAPH.invoke(
        {
            "user_id": user_id,
            "text": text,
            "timezone": timezone,
            "image_id": image_id,
            "client_context": client_context or {},
        }
    )
    response: dict[str, Any] = {
        "run_id": final.get("run_id"),
        "intent": final.get("intent"),
        "answer": final.get("final_answer"),
        "tool_results": [final["tool_result"]] if final.get("tool_result") else [],
        "orchestrator": "langgraph",
    }
    if final.get("pending_action"):
        response["pending_action"] = final["pending_action"]
    return response
