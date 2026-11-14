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
