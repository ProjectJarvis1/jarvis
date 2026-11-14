"""외부 MCP 연결부 시험. 실제 stdio 프로세스(tests/fake_mcp_server.py)로 연결·목록·실행·시간 초과를 본다."""
import asyncio
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import mcp_gateway as g  # noqa: E402

FAKE = str(Path(__file__).with_name("fake_mcp_server.py"))


def _config(tmp_path, env_value="tok"):
    cfg = {"servers": {"fake": {
        "transport": "stdio", "command": sys.executable, "args": [FAKE], "env": {"FAKE_TOKEN": "$FAKE_TOKEN"},
        "tools": {
            "notion_search": {"upstream": "API-post-search", "title": "노션 검색", "description": "검색",
                              "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
                              "fixed": {"page_size": 5}, "shape": "notion_search"},
            "notion_append_note": {"upstream": "API-patch-block-children", "title": "노션에 내용 추가",
                                   "build": "notion_append", "confirm": True, "preview": "추가할까요?\n{text}",
                                   "parameters": {"type": "object", "properties": {"page_id": {"type": "string"}, "text": {"type": "string"}}}},
            "slow_tool": {"upstream": "slow", "title": "느린 도구"},
            "boom_tool": {"upstream": "boom", "title": "실패 도구"},
            "ghost": {"upstream": "does-not-exist", "title": "없는 도구"},
            "who": {"upstream": "whoami", "title": "누구"},
        }}}}
    path = tmp_path / "servers.json"
    path.write_text(json.dumps(cfg))
    return g.load_config(path)


def test_sanitize_schema_keeps_gemini_subset():
    schema = {"$schema": "x", "type": "object", "additionalProperties": False, "$defs": {"a": {}},
              "properties": {"q": {"type": ["string", "null"], "format": "uuid", "default": "", "description": "d"},
                             "sort": {"anyOf": [{"type": "object", "properties": {"dir": {"type": "string", "enum": ["a", "b"]}}}, {"type": "string"}]},
                             "parent": {"$ref": "#/$defs/a"},
                             "tags": {"type": "array", "items": {"type": "string", "const": "x"}}},
              "required": ["q", "missing"]}
    out = g.sanitize_schema(schema)
    assert out == {"type": "object", "properties": {
        "q": {"type": "string", "description": "d"},
        "sort": {"type": "object", "properties": {"dir": {"type": "string", "enum": ["a", "b"]}}},
        "parent": {"type": "string"},
        "tags": {"type": "array", "items": {"type": "string"}}}, "required": ["q"]}


def test_export_name_is_gemini_safe():
    assert g.export_name("API-post.search") == "API_post_search"


def test_server_without_credentials_is_not_listed(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    gw = g.McpGateway(_config(tmp_path), lambda _: None)
    assert gw.manifest() == []
    assert gw.status()["fake"]["credentials"] is False


def test_list_call_shape_confirm_timeout(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_TOKEN", "tok")
    monkeypatch.setattr(g, "CALL_TIMEOUT_S", 2.0)

    async def run():
        gw = g.McpGateway(_config(tmp_path), lambda _: None)
        await gw.start()
        await asyncio.wait_for(gw.connections["fake"].ready.wait(), 20)
        names = {t["name"]: t for t in gw.manifest()}
        assert set(names) == {"notion_search", "notion_append_note", "slow_tool", "boom_tool", "who"}  # 없는 도구는 빠짐
        assert names["notion_append_note"]["annotations"]["requires_confirmation"] is True
        assert names["notion_search"]["gemini"]["parameters"]["required"] == ["query"]
        assert "properties" in names["slow_tool"]["gemini"]["parameters"]  # parameters 를 안 적으면 원본을 다듬어 씀

        found = await gw.call("notion_search", {"query": "견적서"})
        assert found["is_error"] is False
        data = json.loads(found["content"])
        assert data == {"results": [{"id": "p1", "title": "견적서 페이지", "type": "page", "url": "https://n/p1", "last_edited": None}]}

        added = await gw.call("notion_append_note", {"page_id": "p1", "text": "메모"})
        assert json.loads(added["content"])["text"] == "메모"
        assert gw.preview("notion_append_note", {"page_id": "p1", "text": "메모"}) == "추가할까요?\n메모"
        assert gw.preview("notion_search", {"query": "x"}) is None

        failed = await gw.call("boom_tool", {})
        assert failed["is_error"] is True

        timed_out = await gw.call("slow_tool", {})
        assert timed_out["is_error"] is True

        again = await gw.call("notion_search", {"query": "다시"})  # 시간 초과 뒤 다시 연결해서 동작
        assert again["is_error"] is False, again
        await gw.stop()

    asyncio.run(run())


def test_long_notion_text_is_split_not_cut():
    text = "가" * 4000
    built = g._build_notion_append({"page_id": "p1", "text": text})
    contents = [c["paragraph"]["rich_text"][0]["text"]["content"] for c in built["children"]]
    assert "".join(contents) == text and all(len(c) <= 1900 for c in contents)
    with pytest.raises(ValueError):
        g._build_notion_append({"page_id": "p1", "text": "가" * (1900 * 100 + 1)})


def test_env_falls_back_to_connected_account(monkeypatch):
    monkeypatch.delenv("NOTION_TOKEN", raising=False)
    assert g._expand_env("$NOTION_TOKEN|db:notion", lambda p: "db-token" if p == "notion" else None) == "db-token"
    monkeypatch.setenv("NOTION_TOKEN", "env-token")
    assert g._expand_env("$NOTION_TOKEN|db:notion", lambda p: "db-token") == "env-token"


def test_missing_argument_is_a_tool_error_and_late_key_connects(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    monkeypatch.setattr(g, "WATCH_INTERVAL_S", 0.2)

    async def run():
        gw = g.McpGateway(_config(tmp_path), lambda _: None)
        await gw.start()
        assert gw.manifest() == []
        missing = await gw.call("notion_append_note", {"text": "메모"})  # page_id 없음: 500 이 아니라 도구 오류
        assert missing["is_error"] is True and "page_id" in missing["error"]
        monkeypatch.setenv("FAKE_TOKEN", "tok")  # 키를 나중에 넣으면 감시 작업이 연결한다
        await asyncio.wait_for(gw.connections["fake"].ready.wait(), 20)
        assert {t["name"] for t in gw.manifest()} >= {"notion_search"}
        await gw.stop()

    asyncio.run(run())


def test_notion_error_payload_is_not_success(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_TOKEN", "tok")

    class _Conn:
        ready = asyncio.Event()
        running = True

        async def call(self, name, args, **_):
            class R:
                content = [type("T", (), {"text": json.dumps({"object": "error", "status": 401, "code": "unauthorized", "message": "API token is invalid."})})()]
                structured_content = None
                is_error = False
            return R()

    async def run():
        gw = g.McpGateway(_config(tmp_path), lambda _: None)
        gw.connections["fake"] = _Conn()
        res = await gw.call("notion_search", {"query": "x"})
        assert res["is_error"] is True and "API token is invalid" in res["error"]

    asyncio.run(run())


def test_changed_account_token_restarts_connection(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    token = {"v": "account-a"}
    cfg = _config(tmp_path)
    cfg["fake"].env = {"FAKE_TOKEN": "$FAKE_TOKEN|db:fake"}

    async def run():
        gw = g.McpGateway(cfg, lambda p: token["v"] if p == "fake" else None)
        await gw.start()
        conn = gw.connections["fake"]
        await asyncio.wait_for(conn.ready.wait(), 20)
        first = conn._task
        token["v"] = "account-b"
        await gw._reconnect()
        assert conn._task is not first
        await asyncio.wait_for(conn.ready.wait(), 20)
        await gw.stop()

    asyncio.run(run())


def test_timed_out_write_is_marked_uncertain_not_retryable(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_TOKEN", "tok")
    cfg = _config(tmp_path)
    cfg["fake"].tools["slow_tool"].confirm = True  # 쓰기 도구처럼

    class _Conn:
        ready = asyncio.Event()
        running = True

        async def call(self, name, args, **_):
            raise g.SentRequestError(TimeoutError("늦음"))

    async def run():
        gw = g.McpGateway(cfg, lambda _: None)
        gw.connections["fake"] = _Conn()
        write = await gw.call("slow_tool", {})
        assert write["is_error"] and write["uncertain"] and "다시 보내지 말고" in write["error"]
        read = await gw.call("notion_search", {"query": "x"})
        assert read["is_error"] and not read.get("uncertain")

    asyncio.run(run())


def test_notion_string_error_status(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_TOKEN", "tok")
    cfg = _config(tmp_path)

    class _Conn:
        ready = asyncio.Event()
        running = True

        async def call(self, name, args, **_):
            class R:
                content = [type("T", (), {"text": json.dumps({"status": "error", "data": "Bad Gateway"})})()]
                structured_content = None
                is_error = False
            return R()

    async def run():
        gw = g.McpGateway(cfg, lambda _: None)
        gw.connections["fake"] = _Conn()
        read = await gw.call("notion_search", {"query": "x"})
        assert read["is_error"] and not read.get("uncertain")
        write = await gw.call("notion_append_note", {"page_id": "p1", "text": "메모"})
        assert write["is_error"] and write["uncertain"]

    asyncio.run(run())


def test_write_failure_from_mcp_is_uncertain_unless_clearly_rejected():
    assert g._definitely_rejected({"status": 404}, "")
    assert not g._definitely_rejected({"status": 500}, "")
    assert g._definitely_rejected(None, "channel_not_found")
    assert g._definitely_rejected(None, "Invalid argument: title is required")
    assert not g._definitely_rejected(None, "upstream 503 Service Unavailable")
    assert not g._definitely_rejected(None, "request timed out")
    assert not g._definitely_rejected(None, "something went wrong")


def test_disconnected_account_closes_connection_and_hides_tools(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    token = {"v": "account-a"}
    cfg = _config(tmp_path)
    cfg["fake"].env = {"FAKE_TOKEN": "$FAKE_TOKEN|db:fake"}

    async def run():
        gw = g.McpGateway(cfg, lambda p: token["v"] if p == "fake" else None)
        await gw.start()
        conn = gw.connections["fake"]
        await asyncio.wait_for(conn.ready.wait(), 20)
        assert gw.manifest()
        token["v"] = None  # the account was disconnected in the app
        await gw._reconnect()
        assert not conn.ready.is_set() and (conn._task is None or conn._task.done())
        assert gw.manifest() == []
        await gw.stop()

    asyncio.run(run())


# ─── 사용자별 연결 ───────────────────────────────────────────────

def _per_user_config(tmp_path):
    cfg = _config(tmp_path)
    cfg["fake"].env = {"FAKE_TOKEN": "$FAKE_TOKEN|db:fake"}
    return cfg


_TOKENS = {"alice": "tok-alice", "bob": "tok-bob"}


def _provider(auth, user):
    return _TOKENS.get(user) if auth == "fake" else None


def test_each_user_has_own_connection_and_results(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), _provider)
        await gw.start()
        await gw.ensure_user("alice", wait_s=20)
        await gw.ensure_user("bob", wait_s=20)
        assert {t["name"] for t in gw.manifest("alice")} >= {"notion_search", "who"}
        assert gw.manifest("carol") == []  # 연결이 없는 사용자에게는 남의 도구가 보이지 않음
        a, b = await asyncio.gather(gw.call("who", {}, user="alice"), gw.call("who", {}, user="bob"))
        assert json.loads(a["content"])["token"] == "tok-alice"
        assert json.loads(b["content"])["token"] == "tok-bob"
        carol = await gw.call("who", {}, user="carol")
        assert carol["is_error"] and carol["needs_auth"]
        assert not gw._user_conns("carol")["fake"].running  # 키가 없으면 연결 자체를 열지 않음
        await gw.stop()

    asyncio.run(run())


def test_admin_env_key_is_never_used_for_other_users(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_TOKEN", "env-admin")

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), lambda auth, user: None)
        await gw.start()
        await asyncio.wait_for(gw.connections["fake"].ready.wait(), 20)
        assert gw.manifest()  # 관리자는 .env 키로 연결
        await gw.ensure_user("alice", wait_s=1)
        assert gw.manifest("alice") == []
        res = await gw.call("who", {}, user="alice")
        assert res["is_error"] and res["needs_auth"]
        await gw.stop()

    asyncio.run(run())


def test_single_argument_token_lookup_is_admin_only():
    lookup = g._per_user(lambda auth: "admin-token", "default")
    assert lookup("fake", "default") == "admin-token"
    assert lookup("fake", "alice") is None


def test_disconnect_closes_that_users_connection_now(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), _provider)
        await gw.ensure_user("alice", wait_s=20)
        await gw.ensure_user("bob", wait_s=20)
        await gw.close_user("alice", {"slack_user"})  # 다른 서비스 해제는 이 연결과 무관
        assert gw.manifest("alice")
        await gw.close_user("alice", {"fake"})
        assert gw.manifest("alice") == [] and not gw._user_conns("alice")["fake"].running
        assert gw.manifest("bob")  # 다른 사용자는 그대로
        await gw.close_user("bob")  # 계정 삭제: 모두 닫고 잊는다
        assert "bob" not in gw._sessions
        await gw.stop()

    asyncio.run(run())


def test_idle_user_connections_close_but_admin_stays(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_TOKEN", "env-admin")

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), _provider)
        await gw.start()
        await asyncio.wait_for(gw.connections["fake"].ready.wait(), 20)
        await gw.ensure_user("alice", wait_s=20)
        assert gw._user_conns("alice")["fake"].running
        monkeypatch.setattr(g, "IDLE_TIMEOUT_S", 0.0)
        await gw._reconnect()
        assert not gw._user_conns("alice")["fake"].running
        assert gw.connections["fake"].running
        again = await gw.call("who", {}, user="alice")  # 다음에 쓰면 다시 연다
        assert json.loads(again["content"])["token"] == "tok-alice"
        await gw.stop()

    asyncio.run(run())


def test_session_cap_closes_least_recently_used_idle_connection(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    monkeypatch.setattr(g, "MAX_SESSIONS", 1)

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), _provider)
        await gw.ensure_user("alice", wait_s=20)
        assert gw._user_conns("alice")["fake"].running
        await gw.ensure_user("bob", wait_s=20)
        assert gw._user_conns("bob")["fake"].running
        assert not gw._user_conns("alice")["fake"].running
        await gw.stop()

    asyncio.run(run())


def test_concurrent_users_never_exceed_the_session_cap(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    monkeypatch.setattr(g, "MAX_SESSIONS", 1)
    tokens = {"alice": "a", "bob": "b", "carol": "c"}

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), lambda auth, user: tokens.get(user) if auth == "fake" else None)
        await gw.ensure_user("alice", wait_s=20)
        await asyncio.gather(gw.ensure_user("bob", wait_s=20), gw.ensure_user("carol", wait_s=20))
        assert sum(c.running for c in gw._all_conns()) == 1
        await gw.stop()

    asyncio.run(run())


def test_admin_reconnect_respects_the_session_cap(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    monkeypatch.setattr(g, "MAX_SESSIONS", 1)

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), _provider)
        await gw.ensure_user("alice", wait_s=20)
        monkeypatch.setenv("FAKE_TOKEN", "env-admin")  # 관리자 키가 나중에 들어옴
        await gw._reconnect()
        assert sum(c.running for c in gw._all_conns()) == 1 and gw.connections["fake"].running
        await gw.stop()

    asyncio.run(run())


def test_token_lookup_does_not_see_the_request_context(tmp_path, monkeypatch):
    import contextvars
    request_tx = contextvars.ContextVar("request_tx", default=None)
    seen = []

    def provider(auth, user):
        seen.append(request_tx.get())
        return None

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), provider)
        request_tx.set("open transaction")
        await gw.call("who", {}, user="alice")
        await gw.ensure_user("alice", wait_s=0)
        assert seen and all(v is None for v in seen)

    asyncio.run(run())


def test_closing_a_user_cancels_a_connection_still_being_prepared(tmp_path, monkeypatch):
    import threading
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    entered, release = threading.Event(), threading.Event()

    def provider(auth, user):
        if user == "alice" and not release.is_set():
            entered.set()
            release.wait(10)
        return _TOKENS.get(user) if auth == "fake" else None

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), provider)
        task = asyncio.create_task(gw.ensure_user("alice", wait_s=1))
        await asyncio.to_thread(entered.wait, 10)  # 키를 읽는 중에
        prepared = gw._user_conns("alice")["fake"]  # 그 준비가 들고 있는 연결(관리 목록에서 빠질 것)
        await gw.close_user("alice")              # 계정이 지워짐
        release.set()
        await task
        assert not prepared.running  # 관리 밖에 남는 연결이 생기지 않음
        assert not any(c.running for c in gw._all_conns())
        await gw.stop()

    asyncio.run(run())



def test_users_without_open_connections_are_not_kept(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), _provider)
        for i in range(50):
            await gw.ensure_user(f"nobody{i}", wait_s=0)  # 연결한 계정이 없는 사용자들
            await gw.call("who", {}, user=f"nobody{i}")
        assert set(gw._sessions) == {"default"}
        await gw.ensure_user("alice", wait_s=20)
        assert "alice" in gw._sessions  # 연결이 열린 사용자는 남는다
        await gw.close_user("alice")
        await gw.stop()

    asyncio.run(run())


def test_a_connection_closed_mid_call_is_not_reopened_after_the_user_is_removed(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)

    async def run():
        gw = g.McpGateway(_per_user_config(tmp_path), _provider)
        await gw.ensure_user("alice", wait_s=20)
        conn = gw._user_conns("alice")["fake"]
        gen = gw._gen.get("alice", 0)
        await gw.close_user("alice")  # 계정 삭제
        # 앞서 시작된 호출이 이제야 다시 열려고 한다
        assert await gw._admit(conn, gen=gen) is False
        assert not conn.running
        await gw.stop()

    asyncio.run(run())


def test_status_from_a_worker_thread_does_not_register_connections(tmp_path, monkeypatch):
    monkeypatch.delenv("FAKE_TOKEN", raising=False)
    gw = g.McpGateway(_per_user_config(tmp_path), _provider)
    assert gw.status("alice")["fake"]["credentials"] is True
    assert "alice" not in gw._sessions  # 이벤트 루프의 ensure_user 가 만든 묶음과 겹쳐 덮어쓰지 않는다
