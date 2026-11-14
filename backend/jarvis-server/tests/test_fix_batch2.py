"""수정 묶음 2: 검증된 후속 할 일이 비어 있으면 그대로 두고, 회의 원문이 바뀌면 근거가 남은 할 일만 남긴다."""
import os

os.environ.setdefault("DATABASE_URL", os.getenv("TEST_DATABASE_URL") or "postgresql://unused@127.0.0.1:1/unused")

from app.followups import live_followups, normalize_followups, revalidate_followups, stored_followups


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


def test_revalidation_keeps_positions_so_completion_marks_stay_with_their_task():
    # A(0) 는 원문에서 근거가 사라지고 B(1) 는 남는다. B 가 0번으로 당겨지면 앱에 남은 A 의 완료 표시를 물려받는다.
    old = [
        {"task": "A 견적서", "evidence": "견적서는 제가 보낼게요", "due_text": ""},
        {"task": "B 샘플", "evidence": "샘플은 금요일까지 보내요", "due_text": "금요일"},
    ]
    out = revalidate_followups(old, "김 팀장: 샘플은 금요일까지 보내요.")
    assert [i["task"] for i in out] == ["A 견적서", "B 샘플"]
    assert out[0]["stale"] is True and out[0]["due_text"] == "" and not out[1].get("stale")
    # 아침 브리핑과 메일 준비 자료는 stale 을 빼되, B 의 자리는 1번 그대로다.
    assert [(i, it["task"]) for i, it in live_followups(out)] == [(1, "B 샘플")]


def test_restoring_the_transcript_brings_the_due_date_back():
    item = {"task": "B 샘플", "evidence": "샘플은 금요일까지 보내요", "due_text": "금요일까지", "due_at": ""}
    stale = revalidate_followups([item], "샘플 이야기는 취소했어요.")[0]
    assert stale["stale"] and stale["due_text"] == "" and stale["stale_due_text"] == "금요일까지"
    again = revalidate_followups([stale], "김 팀장: 샘플은 금요일까지 보내요.")[0]
    assert not again.get("stale") and again["due_text"] == "금요일까지" and "stale_due_text" not in again
