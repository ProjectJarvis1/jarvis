"""Keep meeting action evidence grounded; leave unsupported assignees/dates unset."""
import re


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
        if not isinstance(due_text, str) or due_text not in evidence:
            due_text = ""
        if not due_text and isinstance(due, str) and due and due in evidence:
            due_text = due
        if not due_text:
            # Keep what follows the date ("쯤", "전에", "경"): the app reads those as approximate or earlier and
            # asks the user, instead of turning "내일쯤" into a firm "내일".
            match = re.search(r"(?:오늘|내일|모레|이번\s*주|다음\s*주|다음\s*달|\d{1,2}월\s*\d{1,2}일)"
                              r"(?:\s*(?:쯤|즈음|무렵|경|전후|이전|전에|전|까지|안에|이내))?", evidence)
            due_text = match.group(0) if match else ""
        result.append({"task": task, "evidence": evidence, "due_text": due_text,
                       "owner": owner if isinstance(owner, str) and owner and owner in evidence else "",
                       "due_at": due if isinstance(due, str) and due and due in evidence else ""})
    return result
