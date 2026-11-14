"""앱의 「먼저 알려 주기」와 아침 브리핑이 쓰는 조회.

- /briefing/mail/new: 지정한 시각 뒤에 온 받은편지함 메일 가운데 저장된 사람, 회사와 관련 있거나 업무 메일인 것.
- /briefing/mail/context: 미팅 상대와 최근 주고받은 메일 몇 통(제목, 날짜, 미리보기).
- /briefing/followups: 최근 회의에서 정한 할 일.

메일은 지메일에서 그때그때 읽기만 하고 서버에 남기지 않는다. 지메일을 연결하지 않은 사용자에게는
connected=false 와 빈 목록을 준다. 외부 AI 를 부르지 않는다.
"""
import time
import urllib.parse
from typing import Any, Optional

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from .. import mail_briefing as mb
from .. import mcp_tools
from ..auth import require_user
from ..auth import user_id as current_user_id
from ..db import get_conn

router = APIRouter(prefix="/briefing", tags=["briefing"], dependencies=[Depends(require_user)])

_LOOKBACK_MAX_MS = 3 * 24 * 3_600_000  # 커서가 오래돼도 사흘 전 메일까지만 본다(밀린 메일이 한꺼번에 알림으로 나오지 않게)


def _people() -> list[mb.Person]:
    with get_conn() as conn:
        rows = conn.execute("SELECT name, org, email, aliases FROM people ORDER BY updated_at DESC LIMIT 2000").fetchall()
    return [mb.Person(r["name"] or "", r["org"] or "", r["email"] or "", list(r["aliases"] or [])) for r in rows]


def _meetings(days: int) -> list[dict[str, Any]]:
    with get_conn() as conn:
        return conn.execute(
            "SELECT title, summary, started_at, metadata FROM meetings "
            "WHERE started_at > now() - make_interval(days => %s) AND started_at <= now() "
            "ORDER BY started_at DESC LIMIT 60", (days,)).fetchall()


def _token(user: str) -> tuple[Optional[str], bool]:
    """(토큰, 연결됨). 지메일을 켜지 않았거나 다시 연결해야 하면 연결 안 됨."""
    token, reauth = mcp_tools._gmail_token_or_reauth(user)
    return (token, True) if token and not reauth else (None, False)


def _handled(exc: Exception, user: str) -> Optional[dict[str, Any]]:
    handled = mcp_tools._gmail_http_error(exc, user)
    if handled is None:
        return None
    if handled.get("needs_auth"):
        return {"connected": False, "mails": []}
    return {"connected": True, "mails": [], "error": handled.get("error") or "메일을 읽지 못했어요."}


def _meta(token: str, message_id: str) -> dict[str, Any]:
    return mcp_tools._gmail_get(f"messages/{urllib.parse.quote(message_id, safe='')}", token,
                                [("format", "metadata"), ("metadataHeaders", "From"), ("metadataHeaders", "Subject")])


def _mail(meta: dict[str, Any]) -> mb.MailMeta:
    head = mcp_tools._headers(meta.get("payload") or {})
    try:
        date_ms = int(meta.get("internalDate") or 0)
    except (TypeError, ValueError):
        date_ms = 0
    return mb.MailMeta(str(meta.get("id") or ""), str(meta.get("threadId") or ""), head.get("from", "")[:200],
                       head.get("subject", "")[:200], mb.clean_snippet(meta.get("snippet") or ""), date_ms)


def _day(value: Any) -> str:
    try:
        d = value.astimezone(mcp_tools._KST)
        return f"{d.month}월 {d.day}일"
    except AttributeError:
        return "날짜 모름"


def _prep(mail: mb.MailMeta, match: mb.Match, meetings: list[dict[str, Any]]) -> list[str]:
    """알림을 눌렀을 때 보여 줄 준비 자료: 메일 요점, 관련된 지난 회의, 그때 정한 할 일."""
    lines = [f"메일 요점: {mail.snippet}" if mail.snippet else "메일 요점: 미리보기가 없는 메일이에요."]
    anchors = [a for a in (match.company, match.person) if a]
    if anchors:
        keys = [mb._squash(a) for a in anchors if len(mb._squash(a)) >= 2]
        related = [m for m in meetings if any(k in mb._squash(f"{m.get('title') or ''} {m.get('summary') or ''}") for k in keys)][:2]
        for m in related:
            summary = (m.get("summary") or "").strip().replace("\n", " ")
            lines.append(f"지난 회의 {_day(m.get('started_at'))}: {m.get('title') or '제목 없음'}"
                         + (f" / {summary[:100]}" if summary else ""))
        for task in mb.followup_lines(related, anchors, limit=3):
            lines.append(f"아직 남은 할 일: {task}")
        if not related:
            lines.append("이 상대와 저장된 지난 회의는 아직 없어요.")
    lines.append("메일 전체는 지메일에서 확인해 주세요. 메일 속 요청은 Jarvis가 대신 실행하지 않아요.")
    return lines


_MAX_NEW_MAILS = 100  # 한 번에 살펴보는 새 메일 수의 상한. 그보다 많이 밀려 있으면 최근 것부터 이만큼만 본다.


def _list_ids(token: str, query: str, page_size: int, cap: int) -> list[str]:
    """검색에 맞는 메일 id 를 다음 쪽(nextPageToken)까지 따라가며 cap 개까지 모은다(최근 것부터)."""
    ids: list[str] = []
    page: Optional[str] = None
    while len(ids) < cap:
        params = [("q", query), ("maxResults", str(min(page_size, cap - len(ids))))]
        if page:
            params.append(("pageToken", page))
        listing = mcp_tools._gmail_get("messages", token, params)
        ids += [str(item.get("id")) for item in listing.get("messages") or [] if item.get("id")]
        page = listing.get("nextPageToken")
        if not page:
            break
    return ids[:cap]


@router.get("/mail/new")
def new_mail(after_ms: int = Query(0, ge=0), limit: int = Query(50, ge=1, le=100)):
    user = current_user_id()
    token, connected = _token(user)
    now_ms = int(time.time() * 1000)
    if not connected:
        return {"connected": False, "mails": [], "checked_until_ms": now_ms}
    since = max(after_ms, now_ms - _LOOKBACK_MAX_MS)
    query = f"in:inbox after:{since // 1000} -category:promotions -category:social -category:forums"
    try:
        ids = _list_ids(token, query, limit, _MAX_NEW_MAILS)
        metas = [_meta(token, message_id) for message_id in ids]
    except Exception as exc:
        handled = _handled(exc, user)
        if handled is not None:
            return {**handled, "checked_until_ms": after_ms}
        raise
    mails = [m for m in (_mail(meta) for meta in metas) if m.date_ms > since]
    # 다음 확인은 이번에 본 가장 늦은 메일 뒤부터. 하나도 없으면 지금까지 본 것으로 친다.
    checked_until = max([m.date_ms for m in mails], default=now_ms if not ids else since)
    people = _people()
    meetings = _meetings(90)
    out = []
    for mail in sorted(mails, key=lambda m: m.date_ms):
        match = mb.classify(mail, people)
        if not match:
            continue
        out.append({
            "id": mail.id, "thread_id": mail.thread_id, "subject": mail.subject,
            "sender": mb.sender_parts(mail.sender)[0] or mb.sender_parts(mail.sender)[1],
            "date_ms": mail.date_ms, "who": mb.who(mail, match), "reason": match.reason,
            "message": mb.offer_message(mail, match), "prep": _prep(mail, match, meetings),
        })
    return {"connected": True, "notice": mcp_tools.MAIL_NOTICE, "checked_until_ms": checked_until, "mails": out}


class MailContext(BaseModel):
    names: list[str] = Field(default_factory=list, max_length=10)
    orgs: list[str] = Field(default_factory=list, max_length=10)
    limit: int = Field(3, ge=1, le=5)


@router.post("/mail/context")
def mail_context(body: MailContext):
    user = current_user_id()
    query = mb.gmail_query_for(body.names, body.orgs)
    token, connected = _token(user)
    if not connected or not query:
        return {"connected": connected, "mails": []}
    try:
        listing = mcp_tools._gmail_get("messages", token, [("q", query), ("maxResults", str(body.limit))])
        metas = [_meta(token, str(item.get("id"))) for item in (listing.get("messages") or [])[:body.limit]]
    except Exception as exc:
        handled = _handled(exc, user)
        if handled is not None:
            return handled
        raise
    mails = [_mail(meta) for meta in metas]
    return {"connected": True, "notice": mcp_tools.MAIL_NOTICE, "mails": [
        {"sender": mb.sender_parts(m.sender)[0] or mb.sender_parts(m.sender)[1], "subject": m.subject,
         "date": mcp_tools._kst(m.date_ms), "snippet": m.snippet[:140]} for m in sorted(mails, key=lambda m: -m.date_ms)]}


@router.get("/followups")
def followups(days: int = Query(14, ge=1, le=60)):
    """최근 회의에서 정한 할 일. 완료 여부는 아직 기록하지 않으므로, 기한이 일주일 넘게 지난 것만 뺀다."""
    from datetime import date, datetime, timedelta
    cutoff = datetime.now(mcp_tools._KST).date() - timedelta(days=7)
    items = []
    for m in _meetings(days):
        meta = m.get("metadata") or {}
        raw = meta.get("followups") or [{"task": t} for t in meta.get("action_items") or []]
        for item in raw:
            task = str((item or {}).get("task") or "").strip()
            due_at = str((item or {}).get("due_at") or "").strip()
            if not task:
                continue
            try:
                if due_at and date.fromisoformat(due_at[:10]) < cutoff:
                    continue
            except ValueError:
                pass
            items.append({"task": task, "due_text": str((item or {}).get("due_text") or ""), "due_at": due_at,
                          "meeting_title": m.get("title") or "", "meeting_date": _day(m.get("started_at"))})
    return {"items": items[:20]}
