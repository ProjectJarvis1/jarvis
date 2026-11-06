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
        assert set(names) == {"notion_search", "notion_append_note", "slow_tool", "boom_tool"}  # 없는 도구는 빠짐
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

        async def call(self, name, args):
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

        async def call(self, name, args):
            raise g.SentRequestError(TimeoutError("늦음"))

    async def run():
        gw = g.McpGateway(cfg, lambda _: None)
        gw.connections["fake"] = _Conn()
        write = await gw.call("slow_tool", {})
        assert write["is_error"] and write["uncertain"] and "다시 보내지 말고" in write["error"]
        read = await gw.call("notion_search", {"query": "x"})
        assert read["is_error"] and not read.get("uncertain")

    asyncio.run(run())
