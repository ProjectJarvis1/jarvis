"""Offline unit checks. Test doubles do not replace PostgreSQL integration tests."""
import ast
from pathlib import Path
from typing import List, Optional

ROOT = Path(__file__).resolve().parents[1]


def functions(path, names, namespace):
    tree = ast.parse((ROOT / path).read_text())
    selected = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    for node in selected:
        node.decorator_list = []
    exec(compile(ast.Module(body=selected, type_ignores=[]), path, "exec"), namespace)
    return namespace


class Cursor:
    def __init__(self):
        self.calls = []
        self.row = {"id": "meeting-id", "title": "new title", "summary": "corrected", "location": "office",
                    "raw_transcript": "original evidence", "person_ids": [], "started_at": "2026-10-17",
                    "metadata": {"decisions": ["decision"], "action_items": ["follow-up"]}}

    def execute(self, sql, params=()):
        self.calls.append((sql, params))
        return self

    def fetchone(self):
        return self.row


def projection():
    embedded = []
    scope = {"Optional": Optional, "List": List, "Jsonb": lambda x: x,
             "embed_text": lambda text: embedded.append(text) or [0.0]}
    functions("app/meeting_projection.py", {"meeting_memory_text", "sync_meeting_memory"}, scope)
    return scope, embedded


def test_meeting_correction_updates_search_projection_from_returned_record():
    scope, embedded = projection()
    scope["FIELDS"] = {"meeting": ("meetings", {"title", "summary", "location"})}
    functions("app/routers/assistant.py", {"apply_values"}, scope)
    cursor = Cursor()
    scope["apply_values"](cursor, "meeting", "meeting-id", {"title": "new title", "summary": "corrected", "location": "office"})
    assert len(cursor.calls) == 3
    assert "UPDATE meetings" in cursor.calls[0][0]
    assert "UPDATE memories" in cursor.calls[1][0]
    assert "source='derived'" in cursor.calls[1][0]
    assert "corrected" in cursor.calls[1][1][1]
    assert "original evidence" in cursor.calls[1][1][1]
    assert embedded[0] == "corrected"
    assert cursor.calls[1][1][4]["labels"] == []
    assert "DELETE FROM memory_entities" in cursor.calls[2][0]


def test_projection_preserves_metadata_evidence_and_participants():
    scope, _ = projection()
    cursor = Cursor()
    cursor.row["person_ids"] = ["person-id"]
    scope["sync_meeting_memory"](cursor, cursor.row)
    params = cursor.calls[0][1]
    assert "decision" in params[1] and "follow-up" in params[1]
    assert params[3] == ["person-id"]


def test_both_update_routes_use_same_projection_service():
    source = (ROOT / "app/routers/meetings.py").read_text()
    assert "sync_meeting_memory(cur, updated)" in source
    source = (ROOT / "app/routers/assistant.py").read_text()
    assert "sync_meeting_memory(cur, updated)" in source


def test_migrations_have_atomic_ledger_and_all_writer_revision_triggers():
    db = (ROOT / "app/db.py").read_text()
    assert "pg_advisory_xact_lock" in db and "schema_migrations" in db
    sql = (ROOT / "migrations/003_record_integrity.sql").read_text()
    for table in ("people", "meetings", "memories"):
        assert f"CREATE TRIGGER {table}_revision BEFORE UPDATE ON {table}" in sql
    assert "NEW.revision = OLD.revision + 1" in sql
    assert "DROP TABLE" not in sql


def test_followups_reject_ungrounded_evidence():
    scope = functions("app/followups.py", {"normalize_followups"}, {"re": __import__("re")})
    result = scope["normalize_followups"]([
        {"task": "send", "evidence": "not in transcript", "owner": "invented", "due_at": "2026-10-20"}
    ], "actual transcript")
    assert result == []


def test_relative_due_expression_is_preserved_without_invented_iso_date():
    scope = functions("app/followups.py", {"normalize_followups"}, {"re": __import__("re")})
    result = scope["normalize_followups"]([
        {"task": "견적 발송", "owner": "민수", "evidence": "민수: 내일 견적을 보내겠습니다.", "due_at": "2026-10-18T09:00:00+09:00"}
    ], "민수: 내일 견적을 보내겠습니다.")
    assert result[0]["due_text"] == "내일"
    assert result[0]["due_at"] == ""


def test_universal_search_batches_related_reads_and_keeps_needs_per_memory():
    from contextlib import contextmanager
    from types import SimpleNamespace
    rows = [{"id": f"m{i}", "text": "evidence", "_score": .8, "related_person_ids": ["p1"], "related_meeting_id": "meeting1"} for i in range(3)]

    class BatchCursor(Cursor):
        def execute(self, sql, params=()):
            super().execute(sql, params)
            if "SELECT * FROM people" in sql:
                self.result = [{"id": "p1", "name": "person"}]
            elif "SELECT * FROM meetings" in sql:
                self.result = [{"id": "meeting1", "summary": "meeting"}]
            elif "CROSS JOIN LATERAL" in sql:
                self.result = [{"memory_id": row["id"], "id": "need1", "text": "need"} for row in rows]
            else:
                self.result = []
            return self

        def fetchall(self):
            return self.result

    cursor = BatchCursor()

    @contextmanager
    def cursor_context():
        yield cursor

    @contextmanager
    def connection_context():
        yield SimpleNamespace(cursor=cursor_context)

    scope = {"List": List, "MemorySearchRequest": object, "UniversalSearchResult": lambda **kw: kw,
             "PersonCandidate": lambda **kw: kw, "_search_memory_rows": lambda _: rows,
             "get_conn": connection_context, "_NEEDS_MIN_SCORE": .5, "_row_to_memory": lambda x: x}
    functions("app/routers/memory.py", {"universal_search"}, scope)
    output = scope["universal_search"](SimpleNamespace(top_k=5))
    assert len(cursor.calls) == 4
    assert len(output) == 3
    assert all(item["needs"][0]["id"] == "need1" for item in output)
    assert all(item["people"][0]["id"] == "p1" for item in output)
