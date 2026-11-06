"""외부 MCP 서버를 서버가 대신 불러 주는 연결부.

앱은 /mcp/tools 로 한 목록만 받고 /mcp 의 tools/call 로 실행한다. 이 모듈은
mcp_servers.json 에 적힌 외부 서버마다 연결을 하나씩 계속 열어 두고(노션은 node 프로세스라
매번 띄우면 1~2초가 더 든다), 골라 둔 도구만 이름을 바꿔 내보낸다.

연결 하나는 작업(task) 하나가 맡는다. anyio 의 취소 범위는 들어간 작업에서 나와야 하므로
요청은 큐로 넘겨 그 작업 안에서 실행한다.
"""
from __future__ import annotations

import asyncio
import contextlib
import contextvars
import hashlib
import json
import logging
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

log = logging.getLogger("jarvis.mcp")

CONFIG_PATH = Path(__file__).with_name("mcp_servers.json")
CALL_TIMEOUT_S = 20.0
CONNECT_TIMEOUT_S = 25.0
RESULT_LIMIT = 6000
WATCH_INTERVAL_S = 30.0
_NAME_RE = re.compile(r"[^A-Za-z0-9_]")

# Gemini 함수 선언은 OpenAPI 의 일부만 받는다. 아래 키는 지우거나 바꾼다.
_DROP_KEYS = {"$schema", "$id", "$defs", "definitions", "additionalProperties", "default", "title",
              "examples", "const", "format", "pattern", "minLength", "maxLength", "readOnly", "writeOnly"}


def sanitize_schema(schema: Any, depth: int = 0) -> dict[str, Any]:
    """MCP inputSchema(JSON Schema) 를 Gemini 가 받는 모양으로 줄인다.

    $ref 는 풀지 않고 문자열로 바꾼다(외부 도구 대부분은 이름을 고를 때 내가 단순한 parameters 를
    따로 적어 두므로, 여기까지 오는 건 단순한 스키마다). anyOf/oneOf 는 첫 번째 객체형 후보를 쓴다.
    """
    if not isinstance(schema, dict) or depth > 6:
        return {"type": "string"}
    if "$ref" in schema:
        return {"type": "string", **({"description": schema["description"]} if "description" in schema else {})}
    for key in ("anyOf", "oneOf", "allOf"):
        if key in schema:
            options = [o for o in schema[key] if isinstance(o, dict) and o.get("type") != "null"]
            chosen = next((o for o in options if o.get("type") == "object"), options[0] if options else {"type": "string"})
            merged = {k: v for k, v in schema.items() if k not in ("anyOf", "oneOf", "allOf")}
            return sanitize_schema({**chosen, **merged}, depth + 1)
    out: dict[str, Any] = {}
    typ = schema.get("type", "object" if "properties" in schema else "string")
    if isinstance(typ, list):
        typ = next((t for t in typ if t != "null"), "string")
    out["type"] = typ
    if "description" in schema:
        out["description"] = str(schema["description"])[:500]
    if "enum" in schema and typ == "string":
        out["enum"] = [str(v) for v in schema["enum"]]
    if typ == "object":
        props = schema.get("properties") or {}
        out["properties"] = {k: sanitize_schema(v, depth + 1) for k, v in props.items()}
        req = [r for r in schema.get("required", []) if r in props]
        if req:
            out["required"] = req
    elif typ == "array":
        out["items"] = sanitize_schema(schema.get("items") or {"type": "string"}, depth + 1)
    return out


def export_name(name: str) -> str:
    return _NAME_RE.sub("_", name)[:64]


def _expand_env(value: str, token_provider: Optional[Callable[[str], Optional[str]]] = None) -> str:
    """"$NAME" 은 환경 변수, "$NAME|db:provider" 는 환경 변수가 없을 때 계정 연결(DB) 토큰."""
    name, _, fallback = value.partition("|db:")
    expanded = re.sub(r"\$([A-Z0-9_]+)", lambda m: os.getenv(m.group(1), ""), name)
    if not expanded and fallback and token_provider:
        expanded = token_provider(fallback) or ""
    return expanded


# ─── 결과 다듬기·입력 만들기 (도구별) ─────────────────────────────

def _notion_title(item: dict[str, Any]) -> str:
    for prop in (item.get("properties") or {}).values():
        if isinstance(prop, dict) and prop.get("type") == "title":
            return "".join(t.get("plain_text", "") for t in prop.get("title", [])) or "(제목 없음)"
    title = item.get("title")
    if isinstance(title, list):
        return "".join(t.get("plain_text", "") for t in title) or "(제목 없음)"
    return "(제목 없음)"


def _shape_notion_search(data: Any) -> Any:
    if not isinstance(data, dict):
        return data
    return {"results": [
        {"id": r.get("id"), "title": _notion_title(r), "type": r.get("object"),
         "url": r.get("url"), "last_edited": r.get("last_edited_time")}
        for r in data.get("results", [])
    ]}


def _shape_notion_markdown(data: Any) -> Any:
    if isinstance(data, dict) and "markdown" in data:
        return {"markdown": data["markdown"]}
    return data


_NOTION_TEXT_LIMIT = 1900  # 노션 rich_text 한 칸은 2,000자까지
_NOTION_MAX_BLOCKS = 100


def _build_notion_append(args: dict[str, Any]) -> dict[str, Any]:
    text = str(args["text"])
    chunks = [text[i:i + _NOTION_TEXT_LIMIT] for i in range(0, len(text), _NOTION_TEXT_LIMIT)] or [""]
    if len(chunks) > _NOTION_MAX_BLOCKS:
        raise ValueError(f"내용이 너무 깁니다({len(text)}자). 나눠서 요청해 주세요.")
    return {"block_id": args["page_id"], "children": [{
        "object": "block", "type": "paragraph",
        "paragraph": {"rich_text": [{"type": "text", "text": {"content": chunk}}]},
    } for chunk in chunks]}


SHAPERS: dict[str, Callable[[Any], Any]] = {
    "notion_search": _shape_notion_search,
    "notion_markdown": _shape_notion_markdown,
}
BUILDERS: dict[str, Callable[[dict[str, Any]], dict[str, Any]]] = {
    "notion_append": _build_notion_append,
}


# ─── 설정 ────────────────────────────────────────────────────────

@dataclass
class ExportedTool:
    name: str
    server: str
    upstream: str
    title: str
    description: str
    parameters: Optional[dict[str, Any]]
    confirm: bool
    fixed: dict[str, Any] = field(default_factory=dict)
    shape: Optional[str] = None
    build: Optional[str] = None
    preview: Optional[str] = None


@dataclass
class ServerConfig:
    name: str
    transport: str
    tools: dict[str, ExportedTool]
    command: str = ""
    args: list[str] = field(default_factory=list)
    env: dict[str, str] = field(default_factory=dict)
    url: str = ""
    auth: Optional[str] = None


def load_config(path: Path = CONFIG_PATH) -> dict[str, ServerConfig]:
    raw = json.loads(path.read_text())
    servers = {}
    for sname, s in raw.get("servers", {}).items():
        tools = {}
        for tname, t in s.get("tools", {}).items():
            name = export_name(tname)
            tools[name] = ExportedTool(
                name=name, server=sname, upstream=t["upstream"], title=t.get("title", name),
                description=t.get("description", ""), parameters=t.get("parameters"),
                confirm=bool(t.get("confirm", False)), fixed=t.get("fixed", {}),
                shape=t.get("shape"), build=t.get("build"), preview=t.get("preview"),
            )
        servers[sname] = ServerConfig(
            name=sname, transport=s["transport"], tools=tools, command=s.get("command", ""),
            args=s.get("args", []), env=s.get("env", {}), url=s.get("url", ""), auth=s.get("auth"),
        )
    return servers


# ─── 연결 ────────────────────────────────────────────────────────

TokenProvider = Callable[[str], Optional[str]]  # auth 이름 -> 접근 토큰(없으면 None)


class _Connection:
    """외부 서버 하나와의 연결. 끊기면 다음 요청 때 다시 연결한다."""

    def __init__(self, cfg: ServerConfig, token_provider: TokenProvider):
        self.cfg = cfg
        self.token_provider = token_provider
        self.upstream_tools: dict[str, Any] = {}
        self.ready = asyncio.Event()
        self.error: Optional[str] = None
        self._queue: asyncio.Queue = asyncio.Queue()
        self._task: Optional[asyncio.Task] = None
        self._closing = False  # 요청 하나가 실패해 연결을 닫는 중
        self.fingerprint = ""  # 지금 연결이 쓰는 키의 지문

    def credential_fingerprint(self) -> str:
        """지금 쓸 키의 지문. 노션 계정을 다시 연결해 토큰이 바뀌면 달라진다."""
        if self.cfg.transport != "stdio":
            return ""
        joined = "\n".join(_expand_env(v, self.token_provider) for v in self.cfg.env.values())
        return hashlib.sha256(joined.encode()).hexdigest()

    def credentials_present(self) -> bool:
        if self.cfg.transport == "stdio":
            return all(_expand_env(v, self.token_provider) for v in self.cfg.env.values())
        return self.cfg.auth is None or bool(self.token_provider(self.cfg.auth))

    def start(self) -> None:
        if self._task is None or self._task.done():
            self.ready.clear()
            self.error = None
            self._closing = False
            # 연결은 요청보다 오래 산다. 요청의 DB 트랜잭션 같은 문맥 변수를 물려받지 않게 빈 문맥으로 띄운다.
            self._task = asyncio.create_task(self._run(), name=f"mcp:{self.cfg.name}", context=contextvars.Context())

    async def stop(self) -> None:
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass

    @contextlib.asynccontextmanager
    async def _transport(self):
        from mcp import StdioServerParameters
        from mcp.client.stdio import stdio_client
        from mcp.client.streamable_http import create_mcp_http_client, streamable_http_client

        if self.cfg.transport == "stdio":
            env = {"PATH": os.getenv("PATH", "/opt/homebrew/bin:/usr/bin:/bin"), "HOME": os.getenv("HOME", "")}
            env.update({k: _expand_env(v, self.token_provider) for k, v in self.cfg.env.items()})
            async with stdio_client(StdioServerParameters(command=self.cfg.command, args=self.cfg.args, env=env)) as streams:
                yield streams
            return

        import httpx2

        provider, auth_name = self.token_provider, self.cfg.auth

        class _Bearer(httpx2.Auth):  # 요청마다 최신 토큰을 넣는다. 토큰 조회는 DB 라서 작업자 스레드에서
            async def async_auth_flow(self, request):
                token = await asyncio.to_thread(provider, auth_name) if auth_name else None
                if token:
                    request.headers["Authorization"] = f"Bearer {token}"
                yield request

        # streamable_http_client 는 넘겨받은 클라이언트를 닫지 않으므로 여기서 닫는다.
        async with create_mcp_http_client(auth=_Bearer()) as client:
            async with streamable_http_client(self.cfg.url, http_client=client) as streams:
                yield streams

    async def _run(self) -> None:
        from mcp import ClientSession

        try:
            async with self._transport() as streams:
                read, write = streams[0], streams[1]
                async with ClientSession(read, write, read_timeout_seconds=CALL_TIMEOUT_S) as session:
                    await asyncio.wait_for(session.initialize(), CONNECT_TIMEOUT_S)
                    listed = await asyncio.wait_for(session.list_tools(), CONNECT_TIMEOUT_S)
                    self.upstream_tools = {t.name: t for t in listed.tools}
                    missing = [t.upstream for t in self.cfg.tools.values() if t.upstream not in self.upstream_tools]
                    if missing:
                        log.warning("MCP %s: 설정한 도구 %s 가 없음. 실제 목록: %s",
                                    self.cfg.name, missing, sorted(self.upstream_tools))
                    log.info("MCP %s 연결됨 (외부 도구 %d개)", self.cfg.name, len(self.upstream_tools))
                    self.ready.set()
                    while True:
                        name, arguments, future = await self._queue.get()
                        if future.cancelled():
                            continue
                        try:
                            result = await asyncio.wait_for(session.call_tool(name, arguments), CALL_TIMEOUT_S)
                            if not future.cancelled():
                                future.set_result(result)
                        except asyncio.CancelledError:
                            raise
                        except Exception as exc:  # 이 요청만 실패시키고 연결은 다시 연다
                            self._closing = True
                            self.ready.clear()
                            if not future.cancelled():
                                future.set_exception(exc)
                            raise
        except asyncio.CancelledError:
            raise
        except BaseException as exc:
            self.error = _short_error(exc)
            log.warning("MCP %s 연결 끊김: %s", self.cfg.name, self.error)
        finally:
            self.ready.clear()
            while not self._queue.empty():
                _, _, future = self._queue.get_nowait()
                if not future.done():
                    future.set_exception(RuntimeError(f"{self.cfg.name} 연결이 끊겼습니다."))

    async def call(self, name: str, arguments: dict[str, Any]):
        if self._closing and self._task and not self._task.done():
            try:  # 닫히는 중인 연결이 다 정리된 뒤 새로 연다
                await asyncio.wait_for(asyncio.shield(self._task), 10)
            except Exception:
                pass
        if self._task is None or self._task.done():
            self.fingerprint = await asyncio.to_thread(self.credential_fingerprint)
        self.start()
        try:
            await asyncio.wait_for(self.ready.wait(), CONNECT_TIMEOUT_S)
        except asyncio.TimeoutError:
            raise RuntimeError(f"{self.cfg.name} 에 연결하지 못했습니다. {self.error or ''}".strip())
        future = asyncio.get_running_loop().create_future()
        await self._queue.put((name, arguments, future))
        try:
            # 실행 시간 제한은 연결 작업이 꺼낸 뒤에 건다. 여기서는 앞 요청을 기다리는 시간까지 넉넉히 둔다.
            return await asyncio.wait_for(future, CALL_TIMEOUT_S * 3)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            raise SentRequestError(exc) from exc


class SentRequestError(Exception):
    """외부 서버에 요청을 넘긴 뒤 실패. 상대 쪽에서는 실행됐을 수도 있다."""

    def __init__(self, cause: BaseException):
        super().__init__(_short_error(cause))
        self.cause = cause


def _short_error(exc: BaseException) -> str:
    inner = getattr(exc, "exceptions", None)
    while inner:  # ExceptionGroup 은 안쪽 첫 원인을 보여 준다
        exc = inner[0]
        inner = getattr(exc, "exceptions", None)
    return f"{type(exc).__name__}: {exc}"[:300]


def _content_text(result: Any) -> tuple[str, bool]:
    parts = []
    for c in getattr(result, "content", None) or []:
        text = getattr(c, "text", None)
        if text:
            parts.append(text)
    structured = getattr(result, "structured_content", None)
    if not parts and structured is not None:
        parts.append(json.dumps(structured, ensure_ascii=False))
    return "\n".join(parts), bool(getattr(result, "is_error", False))


class McpGateway:
    def __init__(self, servers: dict[str, ServerConfig], token_provider: TokenProvider):
        self.servers = servers
        self.connections = {n: _Connection(s, token_provider) for n, s in servers.items()}
        self.tools: dict[str, ExportedTool] = {t.name: t for s in servers.values() for t in s.tools.values()}

    async def start(self) -> None:
        await self._reconnect()
        self._watcher = asyncio.create_task(self._watch(), name="mcp:watch", context=contextvars.Context())

    async def _reconnect(self) -> None:
        conns = list(self.connections.values())
        # 키 확인은 DB 를 볼 수 있어 작업자 스레드에서, 연결 시작은 이 이벤트 루프에서 한다.
        checks = await asyncio.to_thread(lambda: [(c.credentials_present(), c.credential_fingerprint()) for c in conns])
        for conn, (ok, fingerprint) in zip(conns, checks):
            running = conn._task is not None and not conn._task.done()
            if running and fingerprint != conn.fingerprint:
                log.info("MCP %s 키가 바뀌어 다시 연결", conn.cfg.name)
                await conn.stop()
                running = False
            if not running and ok:
                conn.fingerprint = fingerprint
                conn.start()

    async def _watch(self) -> None:
        """키를 나중에 넣었거나 연결이 끊긴 서버를 30초마다 다시 연다."""
        while True:
            await asyncio.sleep(WATCH_INTERVAL_S)
            try:
                await self._reconnect()
            except Exception:
                log.exception("MCP 재연결 확인 실패")

    async def stop(self) -> None:
        watcher = getattr(self, "_watcher", None)
        if watcher:
            watcher.cancel()
        for conn in self.connections.values():
            await conn.stop()

    def manifest(self) -> list[dict[str, Any]]:
        """연결된 서버의 도구만 내보낸다. 키가 없거나 연결이 안 된 서버는 목록에서 빠진다."""
        out = []
        for tool in self.tools.values():
            conn = self.connections[tool.server]
            if not conn.ready.is_set():
                continue  # 끊긴 연결은 _watch 가 이벤트 루프에서 다시 연다
            upstream = conn.upstream_tools.get(tool.upstream)
            if upstream is None:
                continue
            params = tool.parameters or sanitize_schema(upstream.input_schema or {"type": "object"})
            description = tool.description or (upstream.description or "")[:500]
            out.append({
                "name": tool.name,
                "title": tool.title,
                "description": description,
                "inputSchema": params,
                "annotations": {"requires_confirmation": tool.confirm, "source": tool.server},
                "gemini": {"name": tool.name, "description": description, "parameters": params},
            })
        return out

    def is_external(self, name: str) -> bool:
        return name in self.tools

    def is_ready(self, name: str) -> bool:
        tool = self.tools.get(name)
        return bool(tool) and self.connections[tool.server].ready.is_set()

    def preview(self, name: str, arguments: dict[str, Any]) -> Optional[str]:
        tool = self.tools.get(name)
        if not tool or not tool.confirm:
            return None
        if tool.preview:
            try:
                return tool.preview.format(**arguments)
            except (KeyError, IndexError, ValueError):
                pass
        shown = "\n".join(f"{k}: {v}" for k, v in arguments.items() if k != "page_id")
        return f"{tool.title}을(를) 실행할까요?\n{shown}".strip()

    async def call(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        tool = self.tools[name]
        args = {k: v for k, v in arguments.items() if v is not None}
        missing = [k for k in (tool.parameters or {}).get("required", []) if args.get(k) in (None, "")]
        if missing:
            return {"is_error": True, "error": f"{tool.title}에 필요한 값이 없습니다: {', '.join(missing)}"}
        try:
            if tool.build:
                args = BUILDERS[tool.build](args)
        except (KeyError, TypeError, ValueError) as exc:
            return {"is_error": True, "error": f"{tool.title} 입력이 올바르지 않습니다: {exc}"}
        args = {**tool.fixed, **args}
        try:
            result = await self.connections[tool.server].call(tool.upstream, args)
        except SentRequestError as exc:
            if tool.confirm:
                # 보냈는지 모르는 쓰기는 다시 실행하면 두 번 갈 수 있다. 성공 응답(200)으로 기록해 자동 재시도를 막는다.
                return {"is_error": True, "uncertain": True,
                        "error": f"{tool.title} 응답을 받지 못해 실행됐는지 확인하지 못했습니다. 다시 보내지 말고, 사용자에게 직접 확인해 달라고 안내하세요. ({exc})"}
            return {"is_error": True, "error": f"{tool.title} 실패: {exc}"}
        except Exception as exc:
            return {"is_error": True, "error": f"{tool.title} 실패: {_short_error(exc)}"}
        text, is_error = _content_text(result)
        if is_error:
            return {"is_error": True, "error": text[:1000] or f"{tool.title} 실패"}
        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = None
        # 노션 MCP 는 API 실패(401, 404 등)를 isError 없이 JSON 글로 돌려준다. 성공으로 넘기지 않는다.
        if isinstance(parsed, dict) and (parsed.get("object") == "error" or
                                         (isinstance(parsed.get("status"), int) and parsed["status"] >= 400)):
            return {"is_error": True, "error": f"{tool.title} 실패: {parsed.get('message') or parsed.get('code') or text[:300]}"}
        if tool.shape and parsed is not None:
            try:
                text = json.dumps(SHAPERS[tool.shape](parsed), ensure_ascii=False)
            except (ValueError, TypeError, AttributeError):
                pass
        truncated = len(text) > RESULT_LIMIT
        return {"is_error": False, "content": text[:RESULT_LIMIT] + ("\n…(뒷부분 생략)" if truncated else "")}

    def status(self) -> dict[str, Any]:
        return {n: {"connected": c.ready.is_set(), "credentials": c.credentials_present(), "error": c.error}
                for n, c in self.connections.items()}


gateway: Optional[McpGateway] = None
