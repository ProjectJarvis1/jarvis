"""Who is calling. The app signs in with Google and then sends `Authorization: Bearer <session>`;
scripts may still send the server API key, which acts as the admin user ("default").

The resolved user goes into `current_user`, which app/db.py hands to Postgres so row level
security (migrations/005_users.sql) keeps each user's records apart.
"""
import hashlib
import secrets
from contextvars import ContextVar
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Header, HTTPException, status

from .config import settings

ADMIN_USER = "default"
SESSION_DAYS = 60

current_user: ContextVar[Optional[str]] = ContextVar("current_user", default=None)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _session_user(token: str) -> Optional[str]:
    from .db import pool
    with pool.connection() as conn:
        row = conn.execute(
            "SELECT user_id FROM app_sessions WHERE token_hash = %s AND expires_at > now()",
            (hash_token(token),),
        ).fetchone()
    return row["user_id"] if row else None


def resolve_user(authorization: str = "", api_key: str = "") -> Optional[str]:
    """The user behind these credentials, or None. Does not touch current_user."""
    if api_key and secrets.compare_digest(api_key.encode(), settings.api_key.encode()):
        return ADMIN_USER
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() == "bearer" and token.strip():
        return _session_user(token.strip())
    return None


async def require_api_key(
    x_api_key: str = Header("", alias="X-API-Key"),
    authorization: str = Header("", alias="Authorization"),
) -> str:
    """Router dependency (name kept from the single-key days). Async so the user it sets
    stays in the request's context for the endpoint that follows."""
    from starlette.concurrency import run_in_threadpool
    user = await run_in_threadpool(resolve_user, authorization, x_api_key)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="로그인이 필요합니다.")
    current_user.set(user)
    return user


require_user = require_api_key


def user_id() -> str:
    """The signed-in user for code running inside a request."""
    user = current_user.get()
    if user is None:
        raise RuntimeError("no user in this context")
    return user


def create_session(conn, user: str) -> str:
    token = secrets.token_urlsafe(32)
    conn.execute(
        "INSERT INTO app_sessions(token_hash, user_id, expires_at) VALUES (%s, %s, %s)",
        (hash_token(token), user, datetime.now(timezone.utc) + timedelta(days=SESSION_DAYS)),
    )
    conn.execute("DELETE FROM app_sessions WHERE user_id = %s AND expires_at < now()", (user,))
    return token
