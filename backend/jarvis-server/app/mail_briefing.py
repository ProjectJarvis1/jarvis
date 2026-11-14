"""메일이 오면 먼저 알려 주기와 미팅 브리핑에 넣을 메일 고르기.

메일 본문은 서버에 저장하지 않는다. 지메일에서 제목, 보낸 사람, 짧은 미리보기(snippet)만 받아
저장된 사람, 회사와 맞춰 보고, 업무 메일만 골라 앱에 돌려준다. 고르는 규칙은 아래 순수 함수에 모아
DB 와 지메일 없이 시험할 수 있게 했다.
"""
import re
from dataclasses import dataclass, field
from email.utils import parseaddr
from typing import Any, Iterable, Optional

# 제목이나 미리보기에 이 말이 있으면 업무 메일로 본다. 저장된 사람이나 회사가 아니어도 알린다.
BUSINESS_WORDS = ("기술협상", "협상", "입찰", "견적", "발주", "계약", "제안서", "제안 요청", "RFP", "rfp",
                  "납품", "검수", "미팅", "회의", "방문", "참석 요청", "일정 조율", "PO ", "구매 요청")
# 광고성 메일에 흔한 말. 업무 단어가 있어도 이런 메일은 업무 단어만으로는 알리지 않는다.
NOISE_WORDS = ("수신거부", "광고", "unsubscribe", "뉴스레터", "newsletter", "프로모션", "할인", "이벤트")


@dataclass
class Person:
    name: str
    org: str = ""
    email: str = ""
    aliases: list[str] = field(default_factory=list)

    @property
    def names(self) -> list[str]:
        return [n for n in [self.name, *self.aliases] if n and len(n.strip()) >= 2]


@dataclass
class MailMeta:
    id: str
    thread_id: str
    sender: str
    subject: str
    snippet: str
    date_ms: int


@dataclass
class Match:
    reason: str            # person, company, keyword
    person: str = ""
    company: str = ""
    word: str = ""


def _squash(text: str) -> str:
    return re.sub(r"\s+", "", (text or "").lower())


def sender_parts(sender: str) -> tuple[str, str]:
    """('김 팀장', 'kim@hanbit.example')."""
    name, address = parseaddr(sender or "")
    return name.strip().strip('"'), address.strip().lower()


def classify(mail: MailMeta, people: Iterable[Person]) -> Optional[Match]:
    """이 메일을 먼저 알릴지와 그 이유. 저장된 사람(메일 주소나 이름) > 회사 > 업무 단어 순으로 본다."""
    name, address = sender_parts(mail.sender)
    head = f"{name} {mail.subject}"
    text = f"{head} {mail.snippet}"
    squashed_head = _squash(head)
    squashed_text = _squash(text)
    domain = address.split("@", 1)[1] if "@" in address else ""
    people = list(people)
    for p in people:
        if p.email and address and p.email.strip().lower() == address:
            return Match("person", person=p.name, company=p.org or "")
    for p in people:
        # 이름은 보낸 사람 이름이나 제목에 있어야 한다. 본문 미리보기 속 이름만으로는 그 사람의 메일로 보지 않는다.
        if any(_squash(n) and _squash(n) in squashed_head for n in p.names):
            return Match("person", person=p.name, company=p.org or "")
    for p in people:
        org = _squash(p.org)
        if len(org) >= 2 and (org in squashed_text or (domain and org in domain)):
            return Match("company", person=p.name, company=p.org)
    lowered = text.lower()
    if any(w.lower() in lowered for w in NOISE_WORDS):
        return None
    for w in BUSINESS_WORDS:
        if w.lower() in f"{mail.subject} ".lower():
            return Match("keyword", word=w.strip())
    return None


def who(mail: MailMeta, match: Match) -> str:
    """알림 문장에 넣을 상대: 회사가 있으면 회사, 없으면 사람, 그것도 없으면 보낸 사람 이름이나 주소."""
    if match.company:
        return match.company
    if match.person:
        return f"{match.person} 님"
    name, address = sender_parts(mail.sender)
    return name or address or "누군가"


def offer_message(mail: MailMeta, match: Match) -> str:
    subject = mail.subject.strip() or "(제목 없음)"
    if len(subject) > 40:
        subject = subject[:40].rstrip() + "…"
    return f"방금 {who(mail, match)}에서 '{subject}' 메일이 왔어요. 준비 자료를 모아 드릴까요?"


def clean_snippet(snippet: str) -> str:
    """지메일 미리보기는 HTML 엔터티가 섞여 온다."""
    import html
    return re.sub(r"\s+", " ", html.unescape(snippet or "")).strip()[:200]


def gmail_query_for(names: list[str], orgs: list[str], days: int = 60) -> Optional[str]:
    """미팅 상대와 주고받은 최근 메일을 찾는 지메일 검색어. 찾을 말이 없으면 None."""
    terms = []
    for t in [*names, *orgs]:
        t = (t or "").strip().replace('"', "")
        if len(t) >= 2 and t not in terms:
            terms.append(t)
    if not terms:
        return None
    joined = " OR ".join(f'"{t}"' for t in terms[:6])
    return f"({joined}) newer_than:{days}d -category:promotions -category:social"


def followup_lines(meetings: list[dict[str, Any]], anchors: list[str], limit: int = 4) -> list[str]:
    """저장된 회의 가운데 anchors(사람, 회사)를 말하는 회의의 할 일."""
    keys = [_squash(a) for a in anchors if len(_squash(a)) >= 2]
    out: list[str] = []
    for m in meetings:
        text = _squash(f"{m.get('title') or ''} {m.get('summary') or ''}")
        if keys and not any(k in text for k in keys):
            continue
        meta = m.get("metadata") or {}
        items = meta.get("followups") or [{"task": t} for t in meta.get("action_items") or []]
        for item in items:
            task = str((item or {}).get("task") or "").strip()
            if task:
                due = str((item or {}).get("due_text") or "").strip()
                out.append(task + (f" ({due})" if due else ""))
            if len(out) >= limit:
                return out
    return out
