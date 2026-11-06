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
    # 회의가 직접 만든 기억만 고친다: 회의 저장(source='derived')과 회의 녹음(source='voice', memory_type='meeting_recording').
    # 사용자가 따로 남긴 다른 기억은 같은 회의에 연결돼 있어도 건드리지 않는다.
    _PROJECTED = ("related_meeting_id=%s AND (source='derived' OR "
                  "(source='voice' AND metadata->>'memory_type'='meeting_recording'))")
    metadata = meeting.get("metadata") or {}
    text = meeting_memory_text(meeting.get("title"), meeting.get("summary"),
                               meeting.get("raw_transcript"), meeting.get("location"),
                               metadata.get("decisions") or [], metadata.get("action_items") or [])
    cur.execute("""UPDATE memories SET captured_at=%s, text=%s, embedding=%s,
                   related_person_ids=%s, metadata=metadata || %s
                   WHERE """ + _PROJECTED,
                (meeting["started_at"], text, embed_text(text), meeting["person_ids"],
                 Jsonb({"origin_meeting_id": str(meeting["id"]), "user_note": text,
                        "ai_interpretation": "", "people_text": "", "labels": [], "entities": [],
                        # 녹음 기억에 복사해 둔 요약도 회의의 현재 값으로 덮는다(정정 뒤 예전 결정·할 일이 검색 근거로 남지 않게).
                        **{k: metadata.get(k) for k in ("markdown_summary", "decisions", "action_items", "followups")}}),
                 meeting["id"]))
    # Labels/links extracted from an earlier projection must not survive its correction.
    cur.execute("""DELETE FROM memory_entities WHERE memory_id IN (
                       SELECT id FROM memories WHERE """ + _PROJECTED + """
                   )""", (meeting["id"],))
