"""Run against a disposable pgvector DB: TEST_DATABASE_URL=... pytest tests/.
Embeddings are stubbed; SQL transactions, locks, receipts, edits and undo are real.
"""
import os
import sys
import types
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import pytest

pytestmark = pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="disposable TEST_DATABASE_URL required")


@pytest.fixture(scope="module")
def api():
    os.environ["DATABASE_URL"] = os.environ["TEST_DATABASE_URL"]
    os.environ["API_KEY"] = "test-only-assistant-key"
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import numpy as np
    fake = types.ModuleType("app.embeddings")
    fake.embed_text = lambda text: np.zeros(1024, dtype=np.float32)
    sys.modules["app.embeddings"] = fake
    import psycopg
    with psycopg.connect(os.environ["DATABASE_URL"]) as bootstrap:
        bootstrap.execute((Path(__file__).resolve().parents[1] / "schema.sql").read_text())
    from app.db import open_pool, close_pool, ensure_schema, get_conn
    from app.idempotency import IdempotencyMiddleware
    from app.routers.assistant import router
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    open_pool()
    ensure_schema()
    app = FastAPI()
    app.include_router(router)

    @app.post("/memory/save")
    def save(body: dict):
        with get_conn() as conn:
            row = conn.execute("INSERT INTO memories(captured_at,text) VALUES(now(),%s) RETURNING id", (body["text"],)).fetchone()
            conn.commit()  # Must be deferred until the receipt is committed.
        if body.get("fail"):
            from fastapi import HTTPException
            raise HTTPException(422, "failure after local commit")
        return {"id": str(row["id"])}

    app.add_middleware(IdempotencyMiddleware)
    with TestClient(app) as client:
        client.headers["X-API-Key"] = os.environ["API_KEY"]
        yield client, get_conn
    close_pool()
    sys.modules.pop("app.embeddings", None)


def post(client, path, body=None, key=None):
    return client.post(path, json=body or {}, headers={"Idempotency-Key": key or str(uuid4())})


def test_concurrent_replay_and_conflict(api):
    client, get_conn = api
    key, text = str(uuid4()), str(uuid4())
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: post(client, "/memory/save", {"text": text}, key), range(4)))
    assert all(r.status_code == 200 for r in results)
    assert len({r.json()["id"] for r in results}) == 1
    with get_conn() as conn:
        assert conn.execute("SELECT count(*) AS n FROM memories WHERE text=%s", (text,)).fetchone()["n"] == 1
    assert post(client, "/memory/save", {"text": "different"}, key).status_code == 409


def test_error_rolls_back_handler_commit(api):
    client, get_conn = api
    text = str(uuid4())
    response = post(client, "/memory/save", {"text": text, "fail": True})
    assert response.status_code == 422
    with get_conn() as conn:
        assert conn.execute("SELECT count(*) AS n FROM memories WHERE text=%s", (text,)).fetchone()["n"] == 0


def test_replay_requires_auth(api):
    client, _ = api
    key = str(uuid4())
    assert post(client, "/memory/save", {"text": "private"}, key).status_code == 200
    response = client.post("/memory/save", json={"text": "private"}, headers={"Idempotency-Key": key, "X-API-Key": "wrong"})
    assert response.status_code == 401


def test_preview_apply_undo_and_duplicate_apply(api):
    client, get_conn = api
    record_id = post(client, "/memory/save", {"text": "original"}).json()["id"]
    preview = post(client, "/assistant/changes", {"kind": "memory", "record_id": record_id, "changes": {"text": "corrected"}})
    assert preview.status_code == 200, preview.text
    change_id = preview.json()["id"]
    remote_preview = client.get(f"/assistant/changes/{change_id}")
    assert remote_preview.status_code == 200
    assert remote_preview.json()["before_data"]["values"]["text"] == "original"
    with get_conn() as conn:
        assert conn.execute("SELECT text FROM memories WHERE id=%s", (record_id,)).fetchone()["text"] == "original"
    assert post(client, f"/assistant/changes/{change_id}/apply").status_code == 200
    assert post(client, f"/assistant/changes/{change_id}/apply").status_code == 200
    assert post(client, f"/assistant/changes/{change_id}/undo").status_code == 200
    with get_conn() as conn:
        row = conn.execute("SELECT text,metadata FROM memories WHERE id=%s", (record_id,)).fetchone()
        assert row["text"] == "original"
        assert row["metadata"] == {}


def test_stale_preview_and_stale_undo_rejected(api):
    client, get_conn = api
    record_id = post(client, "/memory/save", {"text": "one"}).json()["id"]
    def preview(text):
        return post(client, "/assistant/changes", {"kind": "memory", "record_id": record_id, "changes": {"text": text}}).json()["id"]
    first, second = preview("two"), preview("three")
    assert post(client, f"/assistant/changes/{first}/apply").status_code == 200
    assert post(client, f"/assistant/changes/{second}/apply").status_code == 409
    third = preview("four")
    assert post(client, f"/assistant/changes/{third}/apply").status_code == 200
    assert post(client, f"/assistant/changes/{first}/undo").status_code == 409


def test_delete_requires_preview_then_apply(api):
    client, get_conn = api
    record_id = post(client, "/memory/save", {"text": "delete this"}).json()["id"]
    preview = post(client, "/assistant/changes", {"kind": "memory", "record_id": record_id, "action": "delete"}).json()
    assert post(client, f"/assistant/changes/{preview['id']}/apply").json()["deleted"] is True
    assert post(client, f"/assistant/changes/{preview['id']}/undo").status_code == 409


def test_invalid_fields_and_missing_followups(api):
    client, _ = api
    response = post(client, "/assistant/changes", {"kind": "person", "record_id": str(uuid4()), "changes": {"id": "bad"}})
    assert response.status_code == 422
    assert client.get(f"/assistant/meetings/{uuid4()}/followups").status_code == 404


def test_followups_leave_invented_owner_and_deadline_empty(api):
    from app.followups import normalize_followups
    transcript = "민수: 견적서를 보내겠습니다."
    items = [
        {"task": "견적서 발송", "evidence": transcript, "owner": "민수", "due_at": "2026-10-20"},
        {"task": "다른 업무", "evidence": "원문에 없는 문장", "owner": "지수", "due_at": ""},
    ]
    output = normalize_followups(items, transcript)
    assert len(output) == 1
    assert output[0]["owner"] == "민수"
    assert output[0]["due_at"] == ""


def test_read_full_record_and_reject_edit_based_on_stale_version(api):
    client, get_conn = api
    record_id = post(client, "/memory/save", {"text": "a" * 5000}).json()["id"]
    original = client.get(f"/assistant/records/memory/{record_id}").json()
    assert len(original["values"]["text"]) == 5000
    with get_conn() as conn:
        conn.execute("UPDATE memories SET text='changed elsewhere' WHERE id=%s", (record_id,))
        conn.commit()
    response = post(client, "/assistant/changes", {"kind": "memory", "record_id": record_id,
                    "changes": {"text": "my edit"}, "expected_version": original["version"]})
    assert response.status_code == 409


def test_meeting_apply_undo_updates_derived_memory_and_clears_stale_summary_metadata(api):
    client, get_conn = api
    with get_conn() as conn:
        meeting_id = conn.execute("""INSERT INTO meetings(title,started_at,summary,metadata)
            VALUES('original title',now(),'original summary','{"markdown_summary":"old markdown","action_items":["old task"]}'::jsonb)
            RETURNING id""").fetchone()["id"]
        memory_id = conn.execute("""INSERT INTO memories(captured_at,text,related_meeting_id,source)
            VALUES(now(),'old projection',%s,'derived') RETURNING id""", (meeting_id,)).fetchone()["id"]
        conn.commit()
    change = post(client, "/assistant/changes", {"kind": "meeting", "record_id": str(meeting_id),
                  "changes": {"summary": "corrected summary"}}).json()["id"]
    applied = post(client, f"/assistant/changes/{change}/apply")
    assert applied.status_code == 200, applied.text
    assert post(client, f"/assistant/changes/{change}/apply").json()["record_id"] == str(meeting_id)
    with get_conn() as conn:
        assert "corrected summary" in conn.execute("SELECT text FROM memories WHERE id=%s", (memory_id,)).fetchone()["text"]
        assert conn.execute("SELECT metadata FROM meetings WHERE id=%s", (meeting_id,)).fetchone()["metadata"]["markdown_summary"] == ""
    assert post(client, f"/assistant/changes/{change}/undo").status_code == 200
    with get_conn() as conn:
        assert "original summary" in conn.execute("SELECT text FROM memories WHERE id=%s", (memory_id,)).fetchone()["text"]
        assert conn.execute("SELECT metadata FROM meetings WHERE id=%s", (meeting_id,)).fetchone()["metadata"]["markdown_summary"] == "old markdown"


def test_schema_upgrade_is_repeatable_and_revision_tracks_legacy_updates(api):
    _, get_conn = api
    from app.db import ensure_schema
    ensure_schema()
    ensure_schema()
    with get_conn() as conn:
        person = conn.execute("INSERT INTO people(name) VALUES('revision test') RETURNING id,revision").fetchone()
        revised = conn.execute("UPDATE people SET name='changed' WHERE id=%s RETURNING revision", (person["id"],)).fetchone()
        assert revised["revision"] == person["revision"] + 1
        conn.commit()
