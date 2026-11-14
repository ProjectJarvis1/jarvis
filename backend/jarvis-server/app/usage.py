"""사용자별, 한국 날짜별 AI 사용량과 하루 한도.

외부 AI 비용은 사용자 한 명이 크게 쓰면 바로 늘어난다. 그래서 비용이 드는 호출마다 오늘 쓴 양을 세고,
한도를 넘으면 호출하기 전에 막는다. 한도는 환경변수로 정하고 0 이면 한도가 없다. 관리자 계정은 세지 않는다.

- live_seconds: 실시간 음성 대화 시간(초). 중계가 끝날 때 쓴 시간만큼 더한다.
- meeting_jobs: 회의 녹음 정리(글로 옮기기 + 요약) 횟수.
- agent_calls: 서버 쪽 에이전트와 Flash 중계 호출 횟수.
"""
import os
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime
from zoneinfo import ZoneInfo

from fastapi import HTTPException

from .auth import ADMIN_USER

KST = ZoneInfo("Asia/Seoul")


@dataclass(frozen=True)
class Kind:
    name: str
    env: str
    default: int
    message: str


LIVE_SECONDS = Kind("live_seconds", "AI_LIMIT_LIVE_MINUTES", 240,
                    "오늘 쓸 수 있는 실시간 대화 시간을 모두 썼어요. 내일 다시 쓸 수 있어요.")
MEETING_JOBS = Kind("meeting_jobs", "AI_LIMIT_MEETINGS", 20,
                    "오늘 정리할 수 있는 회의 녹음 수를 모두 썼어요. 내일 다시 올려 주세요.")
AGENT_CALLS = Kind("agent_calls", "AI_LIMIT_AGENT_CALLS", 1000,
                   "오늘 쓸 수 있는 요청 수를 모두 썼어요. 내일 다시 시도해 주세요.")

LIMIT_STATUS = 429
LIVE_CLOSE_CODE = 4429  # 앱이 「오늘 한도를 다 썼어요」로 읽는 웹소켓 종료 코드


@contextmanager
def _conn(user: str):
    """user 로 행 보안을 맞춘 연결. 작업자 스레드나 웹소켓처럼 요청 문맥이 없는 곳에서도 같은 사용자로 읽고 쓴다."""
    from .auth import current_user
    from .db import _bind_user, pool
    token = current_user.set(user)
    try:
        # 요청이 잡고 있는 거래 연결(재시도 기록용)과 섞이지 않게 따로 빌린다. 사용량은 요청이 실패해도 남아야 한다.
        with pool.connection() as conn:
            _bind_user(conn)
            yield conn
    finally:
        current_user.reset(token)


def today() -> date:
    return datetime.now(KST).date()


def limit(kind: Kind) -> int:
    """하루 한도. 실시간 대화는 분 단위로 정하고 초로 바꾼다. 0 이하나 숫자가 아니면 한도 없음."""
    raw = os.getenv(kind.env, "").strip()
    try:
        value = int(raw) if raw else kind.default
    except ValueError:
        value = kind.default
    if value <= 0:
        return 0
    return value * 60 if kind is LIVE_SECONDS else value


def exempt(user: str) -> bool:
    return user == ADMIN_USER


def used(conn, user: str, kind: Kind) -> int:
    row = conn.execute("SELECT amount FROM ai_usage WHERE user_id = %s AND day = %s AND kind = %s",
                       (user, today(), kind.name)).fetchone()
    return int(row["amount"]) if row else 0


def check(user: str, kind: Kind) -> None:
    """한도를 이미 다 썼으면 429 로 막는다. 쓴 양은 더하지 않는다(실시간 대화처럼 끝나야 양을 아는 경우)."""
    cap = limit(kind)
    if exempt(user) or not cap:
        return
    with _conn(user) as conn:
        if used(conn, user, kind) >= cap:
            raise HTTPException(LIMIT_STATUS, kind.message)


def take(user: str, kind: Kind, amount: int = 1) -> None:
    """한도 안이면 amount 만큼 더하고, 넘으면 더하지 않고 429. 동시에 들어온 요청도 한 줄 갱신으로 함께 센다."""
    cap = limit(kind)
    if exempt(user) or not cap:
        return
    if amount > cap:
        raise HTTPException(LIMIT_STATUS, kind.message)
    with _conn(user) as conn:
        row = conn.execute(
            """
            INSERT INTO ai_usage(user_id, day, kind, amount) VALUES (%s, %s, %s, %s)
            ON CONFLICT (user_id, day, kind) DO UPDATE SET amount = ai_usage.amount + EXCLUDED.amount
            WHERE ai_usage.amount + EXCLUDED.amount <= %s
            RETURNING amount
            """,
            (user, today(), kind.name, amount, cap),
        ).fetchone()
        conn.commit()
    if row is None:
        raise HTTPException(LIMIT_STATUS, kind.message)


def add(user: str, kind: Kind, amount: int) -> None:
    """이미 쓴 양을 더한다(한도를 넘어도 기록은 남긴다). 실패해도 본래 일은 막지 않는다."""
    if exempt(user) or amount <= 0:
        return
    try:
        with _conn(user) as conn:
            conn.execute(
                """
                INSERT INTO ai_usage(user_id, day, kind, amount) VALUES (%s, %s, %s, %s)
                ON CONFLICT (user_id, day, kind) DO UPDATE SET amount = ai_usage.amount + EXCLUDED.amount
                """,
                (user, today(), kind.name, amount),
            )
            conn.commit()
    except Exception:
        import logging
        logging.getLogger("jarvis.usage").warning("사용량 기록 실패(%s)", kind.name)


def refund(user: str, kind: Kind, amount: int = 1, day: date | None = None) -> None:
    """take 로 센 양을 쓰지 않았을 때 되돌린다(0 아래로는 내려가지 않는다). day 를 주면 그 날짜에서 뺀다."""
    if exempt(user) or amount <= 0 or not limit(kind):
        return
    try:
        with _conn(user) as conn:
            conn.execute("UPDATE ai_usage SET amount = GREATEST(amount - %s, 0) WHERE user_id = %s AND day = %s AND kind = %s",
                         (amount, user, day or today(), kind.name))
            conn.commit()
    except Exception:
        pass


def reserve(user: str, kind: Kind, want: int) -> tuple[int, date | None]:
    """want 까지 미리 떼어 둔다(동시에 연 대화도 같은 한도를 나눠 쓰게). (떼어 둔 양, 그 날짜).
    한도가 없거나 관리자면 (want, None), 남은 양이 없으면 (0, 날짜). 남은 양이 want 보다 적으면 남은 만큼만."""
    cap = limit(kind)
    if exempt(user) or not cap:
        return want, None
    for _ in range(3):  # 남은 양을 읽은 뒤 다른 요청이 먼저 뗄 수 있다. 몇 번 다시 시도한다.
        day = today()
        left = remaining(user, kind) or 0
        amount = min(want, left)
        if amount <= 0:
            return 0, day
        try:
            take(user, kind, amount)
            return amount, day
        except HTTPException:
            continue
    return 0, today()


def remaining(user: str, kind: Kind) -> int | None:
    """남은 양. 한도가 없으면 None."""
    cap = limit(kind)
    if exempt(user) or not cap:
        return None
    with _conn(user) as conn:
        return max(0, cap - used(conn, user, kind))
