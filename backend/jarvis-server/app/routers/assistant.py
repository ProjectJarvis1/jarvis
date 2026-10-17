"""Reviewable changes and source-grounded meeting follow-ups."""
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from fastapi.encoders import jsonable_encoder
from pydantic import BaseModel, Field
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..db import get_conn
from ..embeddings import embed_text
from ..meeting_projection import sync_meeting_memory

router = APIRouter(prefix="/assistant", tags=["assistant"], dependencies=[Depends(require_api_key)])
FIELDS = {
    "memory": ("memories", {"text"}),
    "person": ("people", {"name", "org", "role", "phone", "email", "notes_summary"}),
    "meeting": ("meetings", {"title", "summary", "location"}),
}


class ChangeRequest(BaseModel):
    kind: Literal["memory", "person", "meeting"]
    record_id: UUID
    changes: dict[str, str] = Field(default_factory=dict)
    action: Literal["update", "delete"] = "update"
    expected_version: str | None = None


def validate_change(body):
    table, fields = FIELDS[body.kind]
    if body.action == "delete":
        if body.kind != "memory":
            raise HTTPException(422, "Only memory deletion is supported")
    elif not body.changes or not set(body.changes).issubset(fields):
        raise HTTPException(422, "Unsupported or empty correction fields")
    if any(len(v) > 12000 for v in body.changes.values()):
        raise HTTPException(422, "Correction too long")
    if "name" in body.changes and not body.changes["name"].strip():
        raise HTTPException(422, "Name must not be empty")
    return table, fields


def record(cur, kind, record_id):
    table, fields = FIELDS[kind]
    row = cur.execute(f"SELECT * FROM {table} WHERE id=%s FOR UPDATE", (record_id,)).fetchone()
    if not row:
        raise HTTPException(404, "Record not found")
    # A persistent revision is advanced by triggers for every writer, including legacy routes.
    version = cur.execute(f"SELECT revision::text AS version FROM {table} WHERE id=%s", (record_id,)).fetchone()["version"]
    snapshot = {"values": {k: row[k] for k in sorted(fields)}, "version": version}
    if kind in {"memory", "meeting"}:
        snapshot["metadata"] = row["metadata"]
    if kind == "memory":
        snapshot["entity_links"] = jsonable_encoder(cur.execute("SELECT entity_id,relation,confidence FROM memory_entities WHERE memory_id=%s", (record_id,)).fetchall())
    return snapshot


@router.get("/capabilities")
def capabilities():
    return {"atomic_idempotency": 1, "reviewable_changes": 1}


@router.get("/records/{kind}/{record_id}")
def read_record(kind: Literal["memory", "person", "meeting"], record_id: UUID):
    with get_conn() as conn:
        with conn.cursor() as cur:
            return record(cur, kind, record_id)


@router.post("/changes")
def preview_change(body: ChangeRequest):
    validate_change(body)
    with get_conn() as conn:
        with conn.cursor() as cur:
            before = record(cur, body.kind, body.record_id)
            if body.expected_version is not None and body.expected_version != before["version"]:
                raise HTTPException(409, "Record changed while editing. Reload it")
            after = {"values": before["values"] | body.changes, "action": body.action}
            row = cur.execute("INSERT INTO assistant_changes(kind,record_id,before_data,after_data) VALUES(%s,%s,%s,%s) RETURNING *",
                              (body.kind, body.record_id, Jsonb(before), Jsonb(after))).fetchone()
        conn.commit()
    return jsonable_encoder(row)


@router.get("/changes/{change_id}")
def read_change(change_id: UUID):
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM assistant_changes WHERE id=%s", (change_id,)).fetchone()
    if not row:
        raise HTTPException(404, "Change not found")
    return jsonable_encoder(row)


def apply_values(cur, kind, record_id, values, metadata=None):
    table, fields = FIELDS[kind]
    columns = sorted(fields)
    params = [values[c] for c in columns]
    clause = ", ".join(f"{c}=%s" for c in columns)
    if kind == "memory":
        clause += ", embedding=%s"
        params.append(embed_text(values["text"]))
        # A manual correction supersedes extracted search labels from the old text.
        clause += ", metadata=metadata || %s"
        params.append(Jsonb({"manually_corrected": True, "user_note": values["text"], "ai_interpretation": "",
                             "people_text": "", "labels": [], "entities": []}))
        cur.execute("DELETE FROM memory_entities WHERE memory_id=%s", (record_id,))
    if kind == "meeting":
        clause += ", summary_embedding=%s"
        params.append(embed_text(values["summary"] or ""))
        if metadata is not None:
            clause += ", metadata=%s"
            params.append(Jsonb(metadata))
    if kind == "person":
        clause += ", updated_at=now()"
    cur.execute(f"UPDATE {table} SET {clause} WHERE id=%s RETURNING *", (*params, record_id))
    updated = cur.fetchone()
    if kind == "meeting":
        sync_meeting_memory(cur, updated)


def change_state(change_id, undo=False):
    with get_conn() as conn:
        with conn.cursor() as cur:
            change = cur.execute("SELECT * FROM assistant_changes WHERE id=%s FOR UPDATE", (change_id,)).fetchone()
            if not change:
                raise HTTPException(404, "Change not found")
            target = "undone" if undo else "applied"
            if change["state"] == target:
                return {"change_id": str(change_id), "record_id": str(change["record_id"]), "state": target, "deleted": change["after_data"].get("action") == "delete"}
            if change["state"] != ("applied" if undo else "preview"):
                raise HTTPException(409, "Change is no longer applicable")
            if undo and change["after_data"].get("action") == "delete":
                raise HTTPException(409, "Deleted memories cannot be restored by this operation")
            current = record(cur, change["kind"], change["record_id"])
            expected = change["after_data"] if undo else change["before_data"]
            if current["version"] != expected["version"]:
                raise HTTPException(409, "Record changed. Create a new preview before continuing")
            if change["after_data"].get("action") == "delete":
                cur.execute("DELETE FROM memories WHERE id=%s", (change["record_id"],))
            else:
                values = change["before_data"]["values"] if undo else change["after_data"]["values"]
                metadata = None
                if change["kind"] == "meeting":
                    metadata = dict(change["before_data"].get("metadata") or {}) if undo else dict(current.get("metadata") or {})
                    if not undo and values["summary"] != current["values"]["summary"]:
                        metadata.update({"manually_corrected": True, "markdown_summary": "", "decisions": [],
                                         "action_items": [], "followups": []})
                apply_values(cur, change["kind"], change["record_id"], values, metadata)
                if undo and change["kind"] == "memory":
                    saved = change["before_data"]
                    cur.execute("UPDATE memories SET metadata=%s WHERE id=%s", (Jsonb(saved["metadata"]), change["record_id"]))
                    for link in saved.get("entity_links", []):
                        cur.execute("INSERT INTO memory_entities(memory_id,entity_id,relation,confidence) VALUES(%s,%s,%s,%s)",
                                    (change["record_id"], link["entity_id"], link["relation"], link["confidence"]))
                after = change["after_data"] | record(cur, change["kind"], change["record_id"])
                cur.execute("UPDATE assistant_changes SET after_data=%s WHERE id=%s", (Jsonb(after), change_id))
            cur.execute("UPDATE assistant_changes SET state=%s WHERE id=%s", (target, change_id))
        conn.commit()
    return {"change_id": str(change_id), "record_id": str(change["record_id"]), "state": target,
            "deleted": change["after_data"].get("action") == "delete"}


@router.post("/changes/{change_id}/apply")
def apply_change(change_id: UUID):
    return change_state(change_id)


@router.post("/changes/{change_id}/undo")
def undo_change(change_id: UUID):
    return change_state(change_id, undo=True)


@router.get("/meetings/{meeting_id}/followups")
def followups(meeting_id: UUID):
    with get_conn() as conn:
        row = conn.execute("SELECT id,title,started_at,raw_transcript,metadata FROM meetings WHERE id=%s", (meeting_id,)).fetchone()
    if not row:
        raise HTTPException(404, "Meeting not found")
    metadata = row["metadata"] or {}
    items = metadata.get("followups") or [{"task": str(item), "owner": "", "due_at": "", "evidence": ""} for item in metadata.get("action_items", [])]
    return jsonable_encoder({"meeting_id": meeting_id, "title": row["title"], "started_at": row["started_at"],
                             "items": items, "requires_calendar_confirmation": True})
