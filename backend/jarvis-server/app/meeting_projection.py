"""Meeting is the source of truth; memories are its searchable projection."""
from typing import List, Optional
from psycopg.types.json import Jsonb
from .embeddings import embed_text


def meeting_memory_text(
    title: Optional[str],
    summary: Optional[str],
    transcript: Optional[str],
    location: Optional[str],
    decisions: Optional[List[str]] = None,
    action_items: Optional[List[str]] = None,
) -> str:
    return "\n".join(
        part
        for part in [
            f"미팅: {title}" if title else "미팅 기록",
            f"요약: {summary}" if summary else None,
            f"장소: {location}" if location else None,
            f"결정사항: {', '.join(decisions or [])}" if decisions else None,
            f"할일: {', '.join(action_items or [])}" if action_items else None,
            f"원문: {transcript}" if transcript else None,
        ]
        if part
    )


def sync_meeting_memory(cur, meeting):
    metadata = meeting.get("metadata") or {}
    text = meeting_memory_text(meeting.get("title"), meeting.get("summary"),
                               meeting.get("raw_transcript"), meeting.get("location"),
                               metadata.get("decisions") or [], metadata.get("action_items") or [])
    cur.execute("""UPDATE memories SET captured_at=%s, text=%s, embedding=%s,
                   related_person_ids=%s, metadata=metadata || %s
                   WHERE related_meeting_id=%s AND source='derived'""",
                (meeting["started_at"], text, embed_text(text), meeting["person_ids"],
                 Jsonb({"origin_meeting_id": str(meeting["id"]), "user_note": text,
                        "ai_interpretation": "", "people_text": "", "labels": [], "entities": []}), meeting["id"]))
    # Labels/links extracted from an earlier projection must not survive its correction.
    cur.execute("""DELETE FROM memory_entities WHERE memory_id IN (
                       SELECT id FROM memories WHERE related_meeting_id=%s AND source='derived'
                   )""", (meeting["id"],))
