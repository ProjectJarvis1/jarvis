"""외부 MCP 서버를 서버가 대신 불러 주는 연결부.

앱은 /mcp/tools 로 한 목록만 받고 /mcp 의 tools/call 로 실행한다. 이 모듈은
mcp_servers.json 에 적힌 외부 서버마다, 사용자마다 연결을 따로 연다. 사용자 A 의 노션 토큰으로 연
연결은 A 의 요청만 받으므로 결과가 다른 사용자에게 섞이지 않는다. 골라 둔 도구만 이름을 바꿔 내보낸다.

- 관리자 계정("default")의 연결은 서버가 뜰 때 열어 두고, 키가 .env 에 있어도 된다(예전 방식).
- 다른 사용자의 연결은 그 사용자가 도구 목록을 받거나 도구를 부를 때 열고, 키는 그 사용자의 계정 연결(DB)에서만
  읽는다. .env 키는 관리자 것이라 다른 사용자에게 쓰지 않는다.
- 열어 둔 연결 수에는 상한(MAX_SESSIONS)이 있고, 오래 안 쓴 연결(IDLE_TIMEOUT_S)은 닫는다.
  연결을 해제하거나 계정을 지우면 그 사용자의 연결을 바로 닫는다(close_user).

연결 하나는 작업(task) 하나가 맡는다. anyio 의 취소 범위는 들어간 작업에서 나와야 하므로
요청은 큐로 넘겨 그 작업 안에서 실행한다.
"""
from __future__ import annotations

import asyncio
import contextlib
import contextvars
import hashlib
import inspect
import json
import logging
import os
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

log = logging.getLogger("jarvis.mcp")

CONFIG_PATH = Path(__file__).with_name("mcp_servers.json")
CALL_TIMEOUT_S = 20.0
CONNECT_TIMEOUT_S = 25.0
RESULT_LIMIT = 6000
WATCH_INTERVAL_S = 30.0


def _env_number(name: str, default: float, minimum: float) -> float:
    """운영에서 바꿀 수 있는 값(D80). 비었거나 숫자가 아니거나 너무 작으면 기본값을 쓰고 로그를 남긴다."""
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        log.warning("%s=%r 는 숫자가 아니라 기본값 %s 를 씀", name, raw, default)
        return default
    if value < minimum:
        log.warning("%s=%s 는 %s 보다 작아 기본값 %s 를 씀", name, raw, minimum, default)
        return default
    return value


# 노션과 슬랙을 합쳐, 모든 사용자가 동시에 열어 두는 외부 연결 수(관리자 연결 포함). 실행 중인 요청이 있는 연결은
# 상한 정리와 유휴 종료에서 닫지 않고, 자리가 없으면 "잠시 뒤에 다시 시도해 주세요"로 안내한다.
MAX_SESSIONS = int(_env_number("MCP_MAX_SESSIONS", 40, 1))
# 관리자 밖 사용자의 연결은 이만큼(초) 안 쓰면 닫는다.
IDLE_TIMEOUT_S = _env_number("MCP_IDLE_TIMEOUT_S", 600.0, 30.0)
USER_WAIT_S = 6.0          # 도구 목록을 줄 때 새로 연 연결을 기다리는 시간
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


def _expand_env(value: str, token_provider: Optional[Callable[[str], Optional[str]]] = None,
                allow_env: bool = True) -> str:
    """"$NAME" 은 환경 변수, "$NAME|db:provider" 는 환경 변수가 없을 때 계정 연결(DB) 토큰.
    allow_env=False(관리자가 아닌 사용자)면 환경 변수는 보지 않고 그 사용자의 계정 연결만 쓴다."""
    name, _, fallback = value.partition("|db:")
    expanded = re.sub(r"\$([A-Z0-9_]+)", lambda m: os.getenv(m.group(1), ""), name) if allow_env else ""
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
def _build_notion_fetch(args: dict[str, Any]) -> dict[str, Any]:
    return {"id": str(args["page_id"])}


def _build_notion_create(args: dict[str, Any]) -> dict[str, Any]:
    # 노션 원격 MCP 의 notion-create-pages. 부모를 안 주면 내 개인 페이지로 만든다.
    return {"pages": [{"properties": {"title": str(args["title"])}, "content": str(args["content"])}]}


BUILDERS: dict[str, Callable[[dict[str, Any]], dict[str, Any]]] = {
    "notion_append": _build_notion_append,
    "notion_fetch": _build_notion_fetch,
    "notion_create": _build_notion_create,
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

    def __init__(self, cfg: ServerConfig, token_provider: TokenProvider, user: str = "default", allow_env: bool = True):
        self.cfg = cfg
        self.token_provider = token_provider
        self.user = user
        self.allow_env = allow_env
        self.last_used = time.monotonic()
        self.in_flight = 0  # 지금 이 연결에서 실행 중인 요청 수. 실행 중인 연결은 상한 정리 때 닫지 않는다
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
        joined = "\n".join(_expand_env(v, self.token_provider, self.allow_env) for v in self.cfg.env.values())
        return hashlib.sha256(joined.encode()).hexdigest()

    def credentials_present(self) -> bool:
        if self.cfg.transport == "stdio":
            return all(_expand_env(v, self.token_provider, self.allow_env) for v in self.cfg.env.values())
        return self.cfg.auth is None or bool(self.token_provider(self.cfg.auth))

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    def start(self) -> None:
        if self._task is None or self._task.done():
            self.ready.clear()
            self.error = None
            self._closing = False
            # 연결은 요청보다 오래 산다. 요청의 DB 트랜잭션 같은 문맥 변수를 물려받지 않게 빈 문맥으로 띄운다.
            self._task = asyncio.create_task(self._run(), name=f"mcp:{self.cfg.name}:{self.user}", context=contextvars.Context())

    async def stop(self) -> None:
        if self._task and not self._task.done():
            # 양보하기 전에 닫는 중으로 표시한다. 그 사이 들어온 요청이 취소된 연결에 줄 서지 않고 새로 연다.
            self._closing = True
            self.ready.clear()
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
            env.update({k: _expand_env(v, self.token_provider, self.allow_env) for k, v in self.cfg.env.items()})
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
                    # 줄에서 꺼내지도 못한 요청은 보내지 않은 것이 확실하다: 다시 실행해도 된다.
                    future.set_exception(_NotSent(f"{self.cfg.name} 연결이 끊겼습니다."))

    async def call(self, name: str, arguments: dict[str, Any],
                   admit: Optional[Callable[[], Awaitable[bool]]] = None):
        """admit: 연결을 (다시) 열 때 거치는 관문(게이트웨이의 상한, 세대 확인). 없으면 여기서 바로 연다."""
        self.in_flight += 1
        self.last_used = time.monotonic()
        try:
            return await self._call(name, arguments, admit)
        finally:
            self.in_flight -= 1
            self.last_used = time.monotonic()

    async def _call(self, name: str, arguments: dict[str, Any], admit: Optional[Callable[[], Awaitable[bool]]] = None):
        # 닫히는 연결에 걸린 요청은 보내지 않은 채로 돌아온다. 그때는 한 번 더 연결을 열어 보낸다.
        for attempt in range(2):
            try:
                return await self._call_once(name, arguments, admit)
            except _NotSent:
                if attempt:
                    raise RuntimeError(f"{self.cfg.name} 연결이 닫혀 요청을 보내지 못했습니다. 다시 시도해 주세요.")

    async def _call_once(self, name: str, arguments: dict[str, Any], admit: Optional[Callable[[], Awaitable[bool]]] = None):
        if self._closing and self._task and not self._task.done():
            try:  # 닫히는 중인 연결이 다 정리된 뒤 새로 연다
                await asyncio.wait_for(asyncio.shield(self._task), 10)
            except Exception:
                pass
        if self._task is None or self._task.done():
            if admit is not None:
                # 다시 열 때도 게이트웨이 관문을 거친다: 그사이 닫힌(연결 해제, 계정 삭제) 연결은 되살리지 않는다.
                if not await admit():
                    raise RuntimeError(f"{self.cfg.name} 연결이 닫혀 다시 열지 않았습니다.")
            else:
                self.fingerprint = await _isolated(self.credential_fingerprint)
                self.start()
        try:
            await asyncio.wait_for(self.ready.wait(), CONNECT_TIMEOUT_S)
        except asyncio.TimeoutError:
            if self._closing:  # 기다리는 사이 닫혔다: 보내지 않았으니 다시 열어 보낸다
                raise _NotSent(f"{self.cfg.name} 연결이 닫혔습니다.")
            raise RuntimeError(f"{self.cfg.name} 에 연결하지 못했습니다. {self.error or ''}".strip())
        if self._closing or not self.running:
            raise _NotSent(f"{self.cfg.name} 연결이 닫혔습니다.")
        future = asyncio.get_running_loop().create_future()
        await self._queue.put((name, arguments, future))
        try:
            # 실행 시간 제한은 연결 작업이 꺼낸 뒤에 건다. 여기서는 앞 요청을 기다리는 시간까지 넉넉히 둔다.
            return await asyncio.wait_for(future, CALL_TIMEOUT_S * 3)
        except (asyncio.CancelledError, _NotSent):
            raise
        except Exception as exc:
            raise SentRequestError(exc) from exc


class _NotSent(RuntimeError):
    """연결이 닫혀 상대에게 보내지 못한 요청. 다시 실행해도 두 번 가지 않는다."""


class SentRequestError(Exception):
    """외부 서버에 요청을 넘긴 뒤 실패. 상대 쪽에서는 실행됐을 수도 있다."""

    def __init__(self, cause: BaseException):
        super().__init__(_short_error(cause))
        self.cause = cause



_REJECTED = re.compile(r"\b4(0[0-9]|1[0-9]|2[0-9])\b|invalid|validation|not[ _]found|unauthori[sz]ed|forbidden|permission|"
                       r"required|missing|not_in_channel|channel_not_found|no_permission|올바르지|권한")


def _definitely_rejected(parsed: Any, text: str) -> bool:
    """쓰기 실패가 '요청이 거절돼 반영되지 않음'이 분명한가. 4xx 상태나 입력·권한 오류만 그렇다고 본다."""
    status = parsed.get("status") if isinstance(parsed, dict) else None
    if isinstance(status, int):
        return 400 <= status < 500
    if re.search(r"\b5\d\d\b|timeout|timed out|unavailable|internal", text, re.I):
        return False
    return bool(_REJECTED.search(text.lower()))

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


async def _isolated(fn: Callable[..., Any], *args: Any) -> Any:
    """작업자 스레드에서 빈 문맥으로 실행한다. 키 조회와 토큰 갱신이 요청의 DB 트랜잭션(문맥 변수)을 물려받으면
    갱신한 토큰의 커밋이 그 요청을 따라 늦어지거나 롤백될 수 있다."""
    return await asyncio.to_thread(contextvars.Context().run, fn, *args)


def _per_user(token_provider: Callable[..., Optional[str]], admin_user: str) -> Callable[[str, str], Optional[str]]:
    """토큰 조회를 (auth, user) 로 맞춘다. 사용자를 받지 않는 조회(auth 만)는 관리자 것으로만 쓴다:
    누구에게나 같은 토큰을 주면 남의 계정으로 실행되기 때문이다."""
    try:
        params = [p for p in inspect.signature(token_provider).parameters.values()
                  if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD, p.VAR_POSITIONAL)]
    except (TypeError, ValueError):
        return token_provider
    if len(params) >= 2 or any(p.kind == p.VAR_POSITIONAL for p in params):
        return token_provider
    return lambda auth, user: token_provider(auth) if user == admin_user else None


class McpGateway:
    def __init__(self, servers: dict[str, ServerConfig], token_provider: Callable[..., Optional[str]],
                 admin_user: str = "default"):
        self.servers = servers
        self.admin_user = admin_user
        self._token = _per_user(token_provider, admin_user)
        self._sessions: dict[str, dict[str, _Connection]] = {}
        self._gen: dict[str, int] = {}  # 사용자별 세대. close_user 가 올리면 그 전에 시작한 준비는 연결을 열지 못한다
        self._admit_lock: Optional[asyncio.Lock] = None
        self._preparing: dict[str, int] = {}  # 준비나 호출이 진행 중인 사용자. 이 사용자의 연결 묶음은 정리하지 않는다  # 상한 확인, 정리, 시작을 한 번에 하나씩(이벤트 루프 안에서 만든다)
        # 관리자 연결(예전의 서버 하나짜리 연결). 시험과 상태 확인이 이 이름으로 본다.
        self.connections = self._user_conns(admin_user)
        self.tools: dict[str, ExportedTool] = {t.name: t for s in servers.values() for t in s.tools.values()}

    def _new_conns(self, user: str) -> dict[str, _Connection]:
        def provider(auth: str, user: str = user) -> Optional[str]:
            return self._token(auth, user)
        return {n: _Connection(s, provider, user, allow_env=(user == self.admin_user)) for n, s in self.servers.items()}

    def _user_conns(self, user: str) -> dict[str, _Connection]:
        """사용자의 연결 묶음. 이벤트 루프에서만 부른다(작업자 스레드가 만들면 서로 덮어쓸 수 있다)."""
        conns = self._sessions.get(user)
        if conns is None:
            conns = self._new_conns(user)
            self._sessions[user] = conns
        return conns

    def _all_conns(self) -> list[_Connection]:
        return [c for conns in list(self._sessions.values()) for c in conns.values()]

    async def _make_room(self) -> bool:
        """연결 수가 상한이면, 실행 중인 요청이 없는 가장 오래 안 쓴(관리자 밖) 연결을 닫는다."""
        running = [c for c in self._all_conns() if c.running]
        if len(running) < MAX_SESSIONS:
            return True
        idle = sorted((c for c in running if c.user != self.admin_user and c.in_flight == 0), key=lambda c: c.last_used)
        if not idle:
            log.warning("MCP 연결이 상한(%d)이라 새 연결을 열지 않음", MAX_SESSIONS)
            return False
        await self._close(idle[0])
        return True

    def _lock(self) -> asyncio.Lock:
        if self._admit_lock is None:
            self._admit_lock = asyncio.Lock()
        return self._admit_lock

    async def _admit(self, conn: _Connection, fingerprint: Optional[str] = None, gen: Optional[int] = None) -> bool:
        """연결을 연다. 상한 확인, 오래된 연결 정리, 시작을 잠금 하나로 묶어 동시에 들어온 요청들이 같은 자리를
        나눠 갖고 상한을 넘기지 않게 한다. 이미 열려 있으면 그대로 둔다."""
        async with self._lock():
            # 닫힌 뒤(연결 해제, 계정 삭제)에 끝난 준비는 열지 않는다: 세대가 바뀌었거나 더 이상 관리하는 연결이 아니면.
            if (gen is not None and self._gen.get(conn.user, 0) != gen) or \
                    (self._sessions.get(conn.user) or {}).get(conn.cfg.name) is not conn:
                return False
            if conn.running:
                return True
            if not await self._make_room():
                return False
            conn.fingerprint = fingerprint if fingerprint is not None else await _isolated(conn.credential_fingerprint)
            conn.start()
            return True

    @staticmethod
    async def _close(conn: _Connection) -> None:
        await conn.stop()
        conn.ready.clear()
        conn.fingerprint = ""

    async def start(self) -> None:
        await self._reconnect()
        self._watcher = asyncio.create_task(self._watch(), name="mcp:watch", context=contextvars.Context())

    @contextlib.contextmanager
    def _hold(self, user: str):
        self._preparing[user] = self._preparing.get(user, 0) + 1
        try:
            yield
        finally:
            left = self._preparing[user] - 1
            if left:
                self._preparing[user] = left
            else:
                del self._preparing[user]
            self._prune(user)

    def _prune(self, user: str) -> None:
        """연결이 하나도 열려 있지 않은(관리자 밖) 사용자의 연결 묶음을 잊는다. 사용자가 늘어도 메모리와 감시 대상이
        열린 연결 수만큼만 남는다. 준비나 호출이 진행 중이면 두고, 다음에 쓸 때 새로 만든다."""
        if user == self.admin_user or self._preparing.get(user):
            return
        conns = self._sessions.get(user)
        if conns is not None and not any(c.running or c.in_flight for c in conns.values()):
            del self._sessions[user]

    async def ensure_user(self, user: str, wait_s: float = USER_WAIT_S) -> None:
        """이 사용자의 연결 가운데 키가 있는 것을 연다(이미 열려 있으면 그대로). 새로 연 연결은 wait_s 까지 기다린다.
        키가 없어졌거나 바뀐 연결은 닫거나 다시 연다."""
        with self._hold(user):
            await self._ensure_user(user, wait_s)

    async def _ensure_user(self, user: str, wait_s: float) -> None:
        gen = self._gen.get(user, 0)
        conns = self._user_conns(user)
        checks = await _isolated(lambda: {n: (c.credentials_present(), c.credential_fingerprint())
                                          for n, c in conns.items()})
        now = time.monotonic()
        for name, (ok, fingerprint) in checks.items():
            conn = conns[name]
            conn.last_used = now
            if conn.running and not ok:
                await self._close(conn)
                continue
            if conn.running and fingerprint != conn.fingerprint:
                await self._close(conn)
            if not conn.running and ok:
                await self._admit(conn, fingerprint, gen)
        pending = [asyncio.create_task(c.ready.wait()) for c in conns.values() if c.running and not c.ready.is_set()]
        if pending:
            _, late = await asyncio.wait(pending, timeout=wait_s)
            for task in late:
                task.cancel()

    async def close_user(self, user: str, auths: Optional[set[str]] = None) -> None:
        """사용자의 연결을 바로 닫는다(연결 해제, 계정 삭제). auths 를 주면 그 계정 연결을 쓰는 서버만 닫는다."""
        async with self._lock():
            self._gen[user] = self._gen.get(user, 0) + 1
            conns = self._sessions.get(user) or {}
            for conn in conns.values():
                used = {conn.cfg.auth} | {v.partition("|db:")[2] for v in conn.cfg.env.values()}
                if auths is None or used & auths:
                    await self._close(conn)
            if auths is None and user != self.admin_user:
                self._sessions.pop(user, None)

    async def _reconnect(self) -> None:
        conns = self._all_conns()
        # 키 확인은 DB 를 볼 수 있어 작업자 스레드에서, 연결 시작은 이 이벤트 루프에서 한다.
        checks = await _isolated(lambda: [(c.credentials_present(), c.credential_fingerprint()) if (c.running or c.user == self.admin_user)
                                                  else (False, "") for c in conns])
        now = time.monotonic()
        for conn, (ok, fingerprint) in zip(conns, checks):
            running = conn.running
            admin = conn.user == self.admin_user
            if running and not ok:
                # Account disconnected: stop now so its tools leave the list, not after a later call fails.
                log.info("MCP %s(%s) 키가 없어져 연결을 닫음", conn.cfg.name, conn.user)
                await self._close(conn)
                continue
            if running and fingerprint != conn.fingerprint:
                log.info("MCP %s(%s) 키가 바뀌어 다시 연결", conn.cfg.name, conn.user)
                await conn.stop()
                running = False
                if not admin:
                    # 사용자 연결은 다음에 쓸 때 새 키로 연다.
                    conn.ready.clear()
                    conn.fingerprint = ""
                    continue
            if running and not admin and conn.in_flight == 0 and now - conn.last_used > IDLE_TIMEOUT_S:
                log.info("MCP %s(%s) 오래 쓰지 않아 닫음", conn.cfg.name, conn.user)
                await self._close(conn)
                continue
            if not running and ok and admin:
                await self._admit(conn, fingerprint)
        for user in list(self._sessions):
            self._prune(user)

    async def _watch(self) -> None:
        """키를 나중에 넣었거나 연결이 끊긴 관리자 서버를 30초마다 다시 열고, 쓰지 않는 사용자 연결을 닫는다."""
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
        for conn in self._all_conns():
            await conn.stop()

    def manifest(self, user: Optional[str] = None) -> list[dict[str, Any]]:
        """이 사용자의 연결된 서버 도구만 내보낸다. 키가 없거나 연결이 안 된 서버는 목록에서 빠진다."""
        conns = self._sessions.get(user or self.admin_user) or {}
        out = []
        for tool in self.tools.values():
            conn = conns.get(tool.server)
            if conn is None:
                continue
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

    def is_ready(self, name: str, user: Optional[str] = None) -> bool:
        tool = self.tools.get(name)
        conn = (self._sessions.get(user or self.admin_user) or {}).get(tool.server) if tool else None
        return bool(conn) and conn.ready.is_set()

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

    async def call(self, name: str, arguments: dict[str, Any], user: Optional[str] = None) -> dict[str, Any]:
        tool = self.tools[name]
        user = user or self.admin_user
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
        with self._hold(user):
            return await self._dispatch(tool, args, user)

    async def _dispatch(self, tool: ExportedTool, args: dict[str, Any], user: str) -> dict[str, Any]:
        gen = self._gen.get(user, 0)
        conn = self._user_conns(user)[tool.server]
        if not conn.running:
            # 키가 없으면 남의 키나 빈 키로 연결하지 않고, 연결이 필요하다고 돌려준다(아무것도 실행하지 않음).
            if not await _isolated(conn.credentials_present):
                return {"is_error": True, "needs_auth": True,
                        "error": f"{tool.title}: 계정 연결이 필요합니다. 앱 설정의 서비스 연결에서 연결해 달라고 안내하세요."}
            conn = self._user_conns(user)[tool.server]
            if not await self._admit(conn, gen=gen):
                if self._gen.get(user, 0) != gen:  # 그사이 연결 해제나 계정 삭제
                    return {"is_error": True, "needs_auth": True,
                            "error": f"{tool.title}: 연결이 해제되었습니다. 다시 연결해 달라고 안내하세요."}
                return {"is_error": True, "error": f"{tool.title}: 지금은 연결이 많아 잠시 뒤에 다시 시도해 주세요."}
        try:
            result = await conn.call(tool.upstream, args, admit=lambda: self._admit(conn, gen=gen))
        except SentRequestError as exc:
            if tool.confirm:
                # 보냈는지 모르는 쓰기는 다시 실행하면 두 번 갈 수 있다. 성공 응답(200)으로 기록해 자동 재시도를 막는다.
                return {"is_error": True, "uncertain": True,
                        "error": f"{tool.title} 응답을 받지 못해 실행됐는지 확인하지 못했습니다. 다시 보내지 말고, 사용자에게 직접 확인해 달라고 안내하세요. ({exc})"}
            return {"is_error": True, "error": f"{tool.title} 실패: {exc}"}
        except Exception as exc:
            return {"is_error": True, "error": f"{tool.title} 실패: {_short_error(exc)}"}
        text, is_error = _content_text(result)
        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = None
        if is_error:
            # MCP 가 실패라고 해도 상대 서비스에서 이미 반영됐을 수 있다(5xx, 시간 초과). 쓰기는 요청이 잘못됐다고
            # 분명히 알 수 있는 실패(4xx)만 확정 실패로 두고, 나머지는 다시 실행하지 않게 '확인 필요'로 남긴다.
            if tool.confirm and not _definitely_rejected(parsed, text):
                return {"is_error": True, "uncertain": True,
                        "error": f"{tool.title} 결과를 확인하지 못했습니다({text[:300]}). 다시 보내지 말고 사용자에게 직접 확인해 달라고 안내하세요."}
            return {"is_error": True, "error": text[:1000] or f"{tool.title} 실패"}
        # 노션 MCP 는 API 실패(401, 404 등)를 isError 없이 JSON 글로 돌려준다. 성공으로 넘기지 않는다.
        status = parsed.get("status") if isinstance(parsed, dict) else None
        if isinstance(parsed, dict) and (parsed.get("object") == "error" or status == "error" or
                                         (isinstance(status, int) and status >= 400)):
            detail = parsed.get("message") or parsed.get("code") or parsed.get("data") or text[:300]
            # 5xx 나 상태를 알 수 없는 실패는 상대 쪽에서 반영됐을 수 있다. 쓰기라면 다시 실행하지 않게 표시한다.
            ambiguous = not (isinstance(status, int) and 400 <= status < 500)
            if tool.confirm and ambiguous:
                return {"is_error": True, "uncertain": True,
                        "error": f"{tool.title} 결과를 확인하지 못했습니다({detail}). 다시 보내지 말고 사용자에게 직접 확인해 달라고 안내하세요."}
            return {"is_error": True, "error": f"{tool.title} 실패: {detail}"}
        if tool.shape and parsed is not None:
            try:
                text = json.dumps(SHAPERS[tool.shape](parsed), ensure_ascii=False)
            except (ValueError, TypeError, AttributeError):
                pass
        truncated = len(text) > RESULT_LIMIT
        return {"is_error": False, "content": text[:RESULT_LIMIT] + ("\n…(뒷부분 생략)" if truncated else "")}

    def status(self, user: Optional[str] = None) -> dict[str, Any]:
        # 작업자 스레드에서 불릴 수 있어 연결 묶음을 새로 등록하지 않는다. 없으면 등록하지 않은 임시 묶음으로 키만 본다.
        user = user or self.admin_user
        conns = self._sessions.get(user) or self._new_conns(user)
        return {n: {"connected": c.ready.is_set(), "credentials": c.credentials_present(), "error": c.error}
                for n, c in conns.items()}


gateway: Optional[McpGateway] = None
