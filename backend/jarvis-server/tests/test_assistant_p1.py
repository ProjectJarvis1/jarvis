"""비서 우선순위 1 시험: 메일 고르기 규칙(오프라인), 오류 수집, 사용량 한도, 얼굴 저장 동의, 메일 브리핑 조회(DB).

DB 시험은 TEST_DATABASE_URL 이 있을 때만 돈다. 지메일은 가짜 응답으로 바꿔 끼우고, 문장 임베딩도 가짜를 쓴다.
"""
import os
import sys
import types
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import pytest

os.environ.setdefault("DATABASE_URL", os.getenv("TEST_DATABASE_URL") or "postgresql://unused@127.0.0.1:1/unused")
os.environ.setdefault("API_KEY", "test-only-assistant-key")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import mail_briefing as mb  # noqa: E402

PEOPLE = [mb.Person("김민수", "한빛정밀", "kim@hanbit.example", ["김팀장"]), mb.Person("박지훈", "대성테크", "")]


def mail(sender="누군가 <x@example.com>", subject="안녕하세요", snippet="", ms=1):
    return mb.MailMeta("m1", "t1", sender, subject, snippet, ms)


# ─── 메일 고르기(오프라인) ───────────────────────────────────

def test_saved_person_by_address_wins():
    m = mb.classify(mail("Kim <KIM@hanbit.example>", "자료"), PEOPLE)
    assert m and m.reason == "person" and m.person == "김민수" and m.company == "한빛정밀"


def test_name_in_subject_counts_but_name_only_in_snippet_does_not():
    assert mb.classify(mail(subject="김팀장님 자료 보냅니다"), PEOPLE).person == "김민수"
    assert mb.classify(mail(subject="자료", snippet="김민수 님께 전달 부탁"), PEOPLE) is None


def test_company_in_text_or_sender_domain():
    m = mb.classify(mail("담당자 <a@x.com>", "기술협상 참여 요청", "대성 테크 구매팀입니다"), PEOPLE)
    assert m.reason == "company" and m.company == "대성테크"
    assert mb.classify(mail("a <a@대성테크.kr>", "회신"), PEOPLE).company == "대성테크"


def test_business_word_in_subject_only_and_noise_is_skipped():
    assert mb.classify(mail(subject="RFP 제안 요청드립니다"), []).reason == "keyword"
    assert mb.classify(mail(subject="견적 이벤트 할인", snippet="수신거부"), []) is None
    assert mb.classify(mail(subject="주말 잘 보내세요", snippet="다음 미팅 때 봬요"), []) is None


def test_offer_message_names_company_and_short_subject():
    m = mail("담당 <a@x.com>", "대성테크 기술협상 참여 요청드립니다 " * 3)
    match = mb.classify(m, PEOPLE)
    text = mb.offer_message(m, match)
    assert text.startswith("방금 대성테크에서 '") and text.endswith("메일이 왔어요. 준비 자료를 모아 드릴까요?")
    assert "…" in text


def test_gmail_query_and_followups():
    assert mb.gmail_query_for([], ["a"]) is None
    q = mb.gmail_query_for(["김민수", "김민수"], ['한빛"정밀'])
    assert q.startswith('("김민수" OR "한빛정밀") newer_than:60d')
    meetings = [{"title": "한빛정밀 견적", "summary": "", "metadata": {"followups": [{"task": "견적서 보내기", "due_text": "금요일"}]}},
                {"title": "다른 회사", "summary": "", "metadata": {"action_items": ["무관한 일"]}}]
    assert mb.followup_lines(meetings, ["한빛정밀"]) == ["견적서 보내기 (금요일)"]


def test_usage_limits_from_env(monkeypatch):
    from app import usage
    monkeypatch.setenv("AI_LIMIT_LIVE_MINUTES", "10")
    monkeypatch.setenv("AI_LIMIT_MEETINGS", "0")
    monkeypatch.setenv("AI_LIMIT_AGENT_CALLS", "abc")
    assert usage.limit(usage.LIVE_SECONDS) == 600
    assert usage.limit(usage.MEETING_JOBS) == 0
    assert usage.limit(usage.AGENT_CALLS) == usage.AGENT_CALLS.default
    assert usage.exempt("default")


# ─── DB 시험 ─────────────────────────────────────────────────

db = pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="disposable TEST_DATABASE_URL required")


@pytest.fixture(scope="module")
def api():
    import numpy as np
    fake = types.ModuleType("app.embeddings")
    fake.embed_text = lambda text: np.zeros(1024, dtype=np.float32)
    sys.modules["app.embeddings"] = fake
    import psycopg
    from app.config import settings
    with psycopg.connect(settings.database_url) as bootstrap:
        bootstrap.execute((Path(__file__).resolve().parents[1] / "schema.sql").read_text())
    from app.auth import create_session
    from app.db import ensure_schema, open_pool, pool
    from app.routers import briefing, client_errors, people
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    open_pool()
    ensure_schema()
    users = {}
    with pool.connection() as conn:
        for name in ("p1a", "p1b"):
            uid = f"{name}-{uuid4().hex[:8]}"
            conn.execute("INSERT INTO app_users(id, email, name) VALUES (%s, %s, %s)", (uid, f"{uid}@t", name))
            users[name] = (uid, create_session(conn, uid))
        conn.commit()
    app = FastAPI()
    for r in (briefing.router, client_errors.router, people.router):
        app.include_router(r)
    with TestClient(app) as client:
        yield client, users, pool
    # 다른 시험 파일이 schema.sql 을 다시 돌릴 때 얼굴 이전 단계가 이 사람들을 건드리지 않게 지운다.
    ids = [u for u, _ in users.values()]
    with pool.connection() as conn:
        conn.execute("RESET ROLE")
        for table in ("person_faces", "people", "meetings", "client_errors", "ai_usage"):
            conn.execute(f"DELETE FROM {table} WHERE user_id = ANY(%s)", (ids,))
        conn.execute("DELETE FROM app_users WHERE id = ANY(%s)", (ids,))
        conn.commit()
    sys.modules.pop("app.embeddings", None)
    # 연결 풀은 모듈에 하나뿐이고 닫으면 다시 열 수 없다. 뒤에 도는 DB 시험 파일이 쓰도록 열어 둔다.


def auth(users, name):
    return {"Authorization": f"Bearer {users[name][1]}"}


@db
def test_client_errors_are_stored_per_user_and_capped(api):
    client, users, pool = api
    body = {"stack": "java.lang.IllegalStateException\n" + "x" * 20_000, "app_version": "0.1.0", "flavor": "phone",
            "device": "Pixel", "os_version": "16"}
    r = client.post("/client-errors", json=body, headers=auth(users, "p1a"))
    assert r.status_code == 202 and r.json() == {"stored": True}
    uid = users["p1a"][0]
    with pool.connection() as conn:
        row = conn.execute("SELECT length(stack) AS n, flavor FROM client_errors WHERE user_id = %s", (uid,)).fetchone()
        assert row["n"] == 16_000 and row["flavor"] == "phone"
        for _ in range(60):
            conn.execute("INSERT INTO client_errors(user_id, stack) VALUES (%s, 's')", (uid,))
        conn.commit()
    assert client.post("/client-errors", json=body, headers=auth(users, "p1a")).json() == {"stored": False}
    assert client.post("/client-errors", json=body).status_code == 401


@db
def test_usage_take_limit_refund_and_isolation(api, monkeypatch):
    from fastapi import HTTPException
    from app import usage
    _, users, pool = api
    monkeypatch.setenv("AI_LIMIT_MEETINGS", "2")
    a, b = users["p1a"][0], users["p1b"][0]
    usage.take(a, usage.MEETING_JOBS)
    usage.take(a, usage.MEETING_JOBS)
    with pytest.raises(HTTPException) as err:
        usage.take(a, usage.MEETING_JOBS)
    assert err.value.status_code == 429 and "회의 녹음" in err.value.detail
    usage.take(b, usage.MEETING_JOBS)  # 다른 사람은 따로 센다
    usage.refund(a, usage.MEETING_JOBS)
    usage.take(a, usage.MEETING_JOBS)
    usage.take("default", usage.MEETING_JOBS)  # 관리자는 세지 않는다
    monkeypatch.setenv("AI_LIMIT_LIVE_MINUTES", "1")
    assert usage.remaining(a, usage.LIVE_SECONDS) == 60
    usage.add(a, usage.LIVE_SECONDS, 75)
    assert usage.remaining(a, usage.LIVE_SECONDS) == 0
    with pytest.raises(HTTPException):
        usage.check(a, usage.LIVE_SECONDS)


@db
def test_face_needs_consent_and_records_when(api):
    client, users, pool = api
    face = [0.01] * 512
    r = client.post("/people", json={"name": "최동의", "face_embedding": face}, headers=auth(users, "p1a"))
    assert r.status_code == 422 and "동의" in r.json()["detail"]
    r = client.post("/people", json={"name": "최동의", "org": "세진메디칼"}, headers=auth(users, "p1a"))
    assert r.status_code == 201  # 얼굴 없이 이름만 저장하는 것은 그대로
    pid = r.json()["id"]
    r = client.post("/people", json={"name": "최동의", "face_embedding": face, "face_consent": True}, headers=auth(users, "p1a"))
    assert r.status_code == 201
    with pool.connection() as conn:
        row = conn.execute("SELECT consent_at FROM person_faces WHERE person_id = %s", (pid,)).fetchone()
    assert row and row["consent_at"] is not None
    r = client.post(f"/people/{pid}/faces", json={"image_base64": "AAAA"}, headers=auth(users, "p1a"))
    assert r.status_code == 422 and "동의" in r.json()["detail"]


def _fake_gmail(messages):
    def get(path, token, query=None):
        if path == "messages":
            return {"messages": [{"id": m["id"]} for m in messages]}
        mid = path.split("/", 1)[1]
        m = next(x for x in messages if x["id"] == mid)
        return {"id": m["id"], "threadId": "t-" + m["id"], "internalDate": str(m["ms"]), "snippet": m.get("snippet", ""),
                "payload": {"headers": [{"name": "From", "value": m["from"]}, {"name": "Subject", "value": m["subject"]}]}}
    return get


@db
def test_new_mail_offers_only_business_mail_with_prep(api, monkeypatch):
    from app import mcp_tools
    client, users, pool = api
    uid = users["p1b"][0]
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    with pool.connection() as conn:
        conn.execute("SELECT set_config('role','jarvis_app',false), set_config('app.user_id',%s,false)", (uid,))
        conn.execute("INSERT INTO people(name, org, email) VALUES ('박지훈', '대성테크', 'park@daesung.example')")
        conn.execute("INSERT INTO meetings(title, started_at, summary, metadata) VALUES "
                     "('대성테크 견적 미팅', now() - interval '3 days', '단가 재검토 요청', "
                     "'{\"followups\": [{\"task\": \"수정 견적서 보내기\", \"due_text\": \"다음 주\"}]}')")
        conn.commit()
        conn.execute("RESET ROLE")
    messages = [
        {"id": "a", "from": "박지훈 <park@daesung.example>", "subject": "기술협상 참여 요청", "snippet": "다음 주 화요일 기술협상에 참석 부탁드립니다 &amp; 자료", "ms": now - 60_000},
        {"id": "b", "from": "쇼핑 <ad@shop.example>", "subject": "주말 특가", "snippet": "수신거부", "ms": now - 30_000},
        {"id": "c", "from": "옛날 <old@x.example>", "subject": "견적 요청", "snippet": "", "ms": now - 10 * 24 * 3_600_000},
    ]
    monkeypatch.setattr(mcp_tools, "_gmail_token_or_reauth", lambda user: ("tok", None))
    monkeypatch.setattr(mcp_tools, "_gmail_get", _fake_gmail(messages))
    r = client.get("/briefing/mail/new", params={"after_ms": now - 3_600_000}, headers=auth(users, "p1b")).json()
    assert r["connected"] and [m["id"] for m in r["mails"]] == ["a"]
    offer = r["mails"][0]
    assert offer["message"] == "방금 대성테크에서 '기술협상 참여 요청' 메일이 왔어요. 준비 자료를 모아 드릴까요?"
    assert offer["prep"][0] == "메일 요점: 다음 주 화요일 기술협상에 참석 부탁드립니다 & 자료"
    assert any("대성테크 견적 미팅" in line for line in offer["prep"])
    assert any(line == "아직 남은 할 일: 수정 견적서 보내기 (다음 주)" for line in offer["prep"])
    assert r["checked_until_ms"] == now - 30_000
    # 다른 사용자에게는 이 사람과 회의가 보이지 않는다(회사 이름으로 맞추지 못하고 업무 단어로만 고른다).
    other = client.get("/briefing/mail/new", params={"after_ms": now - 3_600_000}, headers=auth(users, "p1a")).json()
    assert [m["reason"] for m in other["mails"]] == ["keyword"] and "지난 회의" not in " ".join(other["mails"][0]["prep"])
    # 메일 내용은 서버에 남지 않는다.
    with pool.connection() as conn:
        tables = [r["table_name"] for r in conn.execute(
            "SELECT table_name FROM information_schema.columns WHERE column_name IN ('snippet','subject') AND table_schema='public'").fetchall()]
    assert tables == []


@db
def test_mail_endpoints_without_gmail(api, monkeypatch):
    from app import mcp_tools
    client, users, _ = api
    monkeypatch.setattr(mcp_tools, "_gmail_token_or_reauth", lambda user: (None, {"needs_auth": True}))
    assert client.get("/briefing/mail/new", headers=auth(users, "p1a")).json()["connected"] is False
    r = client.post("/briefing/mail/context", json={"names": ["박지훈"]}, headers=auth(users, "p1a")).json()
    assert r == {"connected": False, "mails": []}


@db
def test_mail_context_and_followups(api, monkeypatch):
    from app import mcp_tools
    client, users, _ = api
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    seen = {}

    def get(path, token, query=None):
        if path == "messages":
            seen["q"] = dict(query)["q"]
        return _fake_gmail([{"id": "z", "from": "박지훈 <park@daesung.example>", "subject": "견적 회신", "snippet": "확인했습니다", "ms": now}])(path, token, query)
    monkeypatch.setattr(mcp_tools, "_gmail_token_or_reauth", lambda user: ("tok", None))
    monkeypatch.setattr(mcp_tools, "_gmail_get", get)
    r = client.post("/briefing/mail/context", json={"names": ["박지훈"], "orgs": ["대성테크"]}, headers=auth(users, "p1b")).json()
    assert seen["q"].startswith('("박지훈" OR "대성테크")')
    assert r["mails"][0]["sender"] == "박지훈" and r["mails"][0]["snippet"] == "확인했습니다"
    items = client.get("/briefing/followups", headers=auth(users, "p1b")).json()["items"]
    assert items and items[0]["task"] == "수정 견적서 보내기" and items[0]["meeting_title"] == "대성테크 견적 미팅"
    assert client.get("/briefing/followups", headers=auth(users, "p1a")).json()["items"] == []
