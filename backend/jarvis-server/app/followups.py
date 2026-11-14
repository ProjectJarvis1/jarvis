"""Keep meeting action evidence grounded; leave unsupported assignees/dates unset."""
import re


# Words right after a date that change its meaning. A lone "전"/"경" counts only as its own word ("내일 전화"
# is not "before tomorrow").
_QUALIFIER = r"\s*(?:쯤|즈음|무렵|전후|이전|전에|까지|안에|이내|(?:전|경)(?![가-힣]))"


def _whole_span(text: str, evidence: str) -> bool:
    """The quoted date is a whole date in the evidence, so a cut-off "다음 주" from "다다음 주" is refused."""
    at = evidence.find(text)
    if not text or at < 0 or (at > 0 and re.match(r"[가-힣]", evidence[at - 1])):
        return False
    # ...and is not the front of a compound date ("내일" out of "내일모레").
    return not re.match(r"모레|내일", evidence[at + len(text):])


def normalize_followups(items, transcript: str) -> list[dict]:
    if not isinstance(items, list):
        return []
    result = []
    for item in items[:100]:
        if not isinstance(item, dict):
            continue
        task, evidence = item.get("task"), item.get("evidence")
        if not isinstance(task, str) or not task.strip() or not isinstance(evidence, str) or not evidence.strip() or evidence not in transcript:
            continue
        owner, due = item.get("owner", ""), item.get("due_at", "")
        due_text = item.get("due_text", "")
        if not isinstance(due_text, str) or not _whole_span(due_text, evidence):
            due_text = ""
        if not due_text and isinstance(due, str) and due and _whole_span(due, evidence):
            due_text = due
        if not due_text:
            # Not inside a longer word: "다다음 주" is not "다음 주".
            # Nor the front of a compound: "내일모레" is not "내일", "오늘내일" not "오늘".
            match = re.search(r"(?<![가-힣])(?:오늘|내일|모레|이번\s*주|다음\s*주|다음\s*달|\d{1,2}월\s*\d{1,2}일)"
                              r"(?!모레|내일)", evidence)
            due_text = match.group(0) if match else ""
        if due_text:
            # A date the model quoted without its "쯤"/"전에" is completed from the evidence, so an approximate
            # or earlier date never reads as a firm deadline.
            at = evidence.find(due_text)
            tail = re.match(_QUALIFIER, evidence[at + len(due_text):]) if at >= 0 else None
            if tail:
                due_text += tail.group(0)
        result.append({"task": task, "evidence": evidence, "due_text": due_text,
                       "owner": owner if isinstance(owner, str) and owner and owner in evidence else "",
                       "due_at": due if isinstance(due, str) and due and due in evidence else ""})
    return result


def stored_followups(metadata) -> list[dict]:
    """회의 기록에 남은 후속 할 일.

    서버가 검증한 `followups` 가 있으면 비어 있어도 그대로 쓴다(검증에서 모두 빠졌다는 뜻이다).
    `followups` 필드가 아예 없는 예전 기록만 검증 전 `action_items` 로 대신한다.
    """
    meta = metadata if isinstance(metadata, dict) else {}
    if isinstance(meta.get("followups"), list):
        return [item for item in meta["followups"] if isinstance(item, dict)]
    return [{"task": str(item), "owner": "", "due_text": "", "due_at": "", "evidence": ""}
            for item in meta.get("action_items") or [] if str(item).strip()]


def revalidate_followups(items, transcript: str) -> list[dict]:
    """원문이 바뀐 회의의 후속 할 일을 다시 검증한다. 순서(자리)는 그대로 둔다.

    근거 문장이 새 원문에 남은 할 일은 새 원문으로 다시 검증한 값으로 바꾸고, 근거가 사라진 할 일은
    기한을 지우고 stale 로 표시해 같은 자리에 남긴다. 앱은 완료 여부를 회의 안의 순서로 기억하므로,
    빼 버리면 뒤의 할 일이 앞 할 일의 완료 표시를 물려받는다.
    """
    if not isinstance(items, list):
        return []
    result = []
    for item in items:
        if not isinstance(item, dict):
            continue
        # 앞선 수정으로 근거가 사라졌던 할 일은 그때 치워 둔 기한을 되살려 다시 검증한다(원문을 되돌리면 기한도 돌아온다).
        candidate = {k: v for k, v in item.items() if k not in ("stale", "stale_due_text", "stale_due_at")}
        if item.get("stale"):
            candidate["due_text"] = item.get("stale_due_text") or ""
            candidate["due_at"] = item.get("stale_due_at") or ""
        checked = normalize_followups([candidate], transcript)
        if checked:
            result.append(checked[0])
        else:
            # 근거가 없으니 기한은 쓰지 않되, 원문이 돌아올 때를 위해 따로 보관한다.
            result.append({**candidate, "due_text": "", "due_at": "", "stale": True,
                           "stale_due_text": candidate.get("due_text") or "", "stale_due_at": candidate.get("due_at") or ""})
    return result


def live_followups(items) -> list[tuple[int, dict]]:
    """(자리, 할 일) 목록. 자리는 빈 항목을 뺀 순서로 매기고(앱과 같은 순서), stale 은 자리만 차지하고 뺀다."""
    out = []
    index = 0
    for item in items or []:
        if not isinstance(item, dict) or not str(item.get("task") or "").strip():
            continue
        if not item.get("stale"):
            out.append((index, item))
        index += 1
    return out
