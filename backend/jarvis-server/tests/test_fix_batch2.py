"""수정 묶음 2: 검증된 후속 할 일이 비어 있으면 그대로 두고, 회의 원문이 바뀌면 근거가 남은 할 일만 남긴다."""
import os

os.environ.setdefault("DATABASE_URL", os.getenv("TEST_DATABASE_URL") or "postgresql://unused@127.0.0.1:1/unused")

from app.followups import normalize_followups, stored_followups


def test_verified_empty_followups_do_not_fall_back_to_action_items():
    # 검증에서 모두 빠진 회의: 검증 안 된 action_items 로 다시 만들지 않는다.
    assert stored_followups({"followups": [], "action_items": ["견적서 보내기"]}) == []


def test_records_without_followups_field_still_show_plain_items():
    items = stored_followups({"action_items": ["견적서 보내기", " "]})
    assert [i["task"] for i in items] == ["견적서 보내기"]
    assert stored_followups(None) == []
    assert stored_followups({"followups": [{"task": "a", "evidence": "e"}, "깨진 항목"]}) == [{"task": "a", "evidence": "e"}]


def test_edited_transcript_keeps_only_items_whose_evidence_remains():
    old = [
        {"task": "견적서 보내기", "evidence": "금요일까지 견적서 보내겠습니다", "due_text": "금요일"},
        {"task": "샘플 발송", "evidence": "샘플은 다음 주에 보내요", "due_text": "다음 주"},
    ]
    new_transcript = "김 팀장: 금요일까지 견적서 보내겠습니다. 샘플 이야기는 취소했어요."
    kept = normalize_followups(old, new_transcript)
    assert [i["task"] for i in kept] == ["견적서 보내기"]
    assert kept[0]["due_text"].startswith("금요일")
