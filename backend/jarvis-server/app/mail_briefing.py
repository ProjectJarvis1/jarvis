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
    id: str = ""

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
    person_id: str = ""
    # 같은 이름이 여러 명 저장돼 있고 보낸 사람 정보로 가리지 못했으면 True. 특정 사람의 기록을 붙이지 않는다.
    ambiguous: bool = False
    # True when the match is the sender itself (address, sender name or domain); False when the person or
    # company is only named in the subject or preview, so the mail is about them, not from them.
    from_sender: bool = True


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
    squashed_sender = _squash(name)
    for p in people:
        if p.email and address and p.email.strip().lower() == address:
            return Match("person", person=p.name, company=p.org or "", person_id=p.id)
    # 이름은 보낸 사람 이름이나 제목에 있어야 한다. 본문 미리보기 속 이름만으로는 그 사람의 메일로 보지 않는다.
    named = [(p, [_squash(n) for n in p.names if _squash(n) and _squash(n) in squashed_head]) for p in people]
    named = [(p, hit) for p, hit in named if hit]
    if named:
        if len({(_squash(p.name), _squash(p.org)) for p, _ in named}) > 1:
            # 같은 이름이 여러 명: 보낸 사람 이름이나 주소의 회사로 가린다. 못 가리면 누구의 기록도 붙이지 않는다.
            told = [(p, hit) for p, hit in named
                    if len(_squash(p.org)) >= 2 and (_squash(p.org) in squashed_text or (domain and _squash(p.org) in domain))]
            if len({(_squash(p.name), _squash(p.org)) for p, _ in told}) != 1:
                p, hit = named[0]
                return Match("person", person=p.name, ambiguous=True, from_sender=any(h in squashed_sender for h in hit))
            named = told[:1]
        p, hit = named[0]
        return Match("person", person=p.name, company=p.org or "", person_id=p.id,
                     from_sender=any(h in squashed_sender for h in hit))
    for p in people:
        org = _squash(p.org)
        if len(org) >= 2 and (org in squashed_text or (domain and org in domain)):
            # 회사만 맞았다: 그 회사의 누구인지는 모른다. 특정 직원으로 정하지 않고 회사 단위로 기록을 찾는다.
            return Match("company", company=p.org,
                         from_sender=bool((domain and org in domain) or org in squashed_sender))
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
    name, address = sender_parts(mail.sender)
    sender = f"{name} 님이" if name else (f"{address}에서" if address else "누군가")
    if match.from_sender and match.company:
        return f"방금 {match.company}에서 '{subject}' 메일이 왔어요. 준비 자료를 모아 드릴까요?"
    if match.from_sender:
        return f"방금 {sender} 보낸 '{subject}' 메일이 왔어요. 준비 자료를 모아 드릴까요?"
    # 이름이나 회사가 제목에만 나온 메일: 실제로 보낸 사람을 쓰고, 누구와 관련된 메일인지 따로 말한다.
    about = match.company or (f"{match.person} 님" if match.person else "")
    tail = f" {about} 관련 메일이에요." if about else ""
    return f"방금 {sender} 보낸 '{subject}' 메일이 왔어요.{tail} 준비 자료를 모아 드릴까요?"


def clean_snippet(snippet: str) -> str:
    """지메일 미리보기는 HTML 엔터티가 섞여 온다."""
    import html
    return re.sub(r"\s+", " ", html.unescape(snippet or "")).strip()[:200]


def gmail_query_for(names: list[str], orgs: list[str], days: int = 60, emails: Optional[list[str]] = None) -> Optional[str]:
    """미팅 상대와 주고받은 최근 메일을 찾는 지메일 검색어. 찾을 말이 없으면 None.
    저장된 메일 주소가 있으면 그 주소와 주고받은 메일만 찾는다(같은 이름의 다른 사람 메일이 섞이지 않게).
    주소가 없는 사람은 이름으로, 사람이 하나도 없을 때만 회사 이름으로 찾는다."""
    terms: list[str] = []
    for e in emails or []:
        e = (e or "").strip().lower()
        if "@" in e and " " not in e and '"' not in e:
            for t in (f"from:{e}", f"to:{e}", f"cc:{e}"):
                if t not in terms:
                    terms.append(t)
    words = [*names] if names else [*orgs]
    for t in words:
        t = (t or "").strip().replace('"', "")
        if len(t) >= 2 and f'"{t}"' not in terms:
            terms.append(f'"{t}"')
    if not terms:
        return None
    joined = " OR ".join(terms[:12])
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
