"""Sign in with Google, sign out, and delete the account (Google Play requires in-app deletion)."""
import hashlib
import logging
import secrets

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from ..auth import ADMIN_USER, create_session, hash_token, require_user, user_id
from ..config import settings
from ..db import get_conn, pool
from ..media import remove_user_files

log = logging.getLogger("jarvis.account")
router = APIRouter(prefix="/auth", tags=["auth"])

# Every table under row level security, children first.
_USER_TABLES = ["memory_entities", "agent_tool_calls", "assistant_changes", "pending_actions", "agent_runs",
                "person_faces", "proposal_points", "needs", "memories", "entities", "meetings", "people",
                "client_errors", "ai_usage"]


class GoogleLogin(BaseModel):
    id_token: str


class DevLogin(BaseModel):
    email: str


def _verify_google(token: str) -> dict:
    from google.auth.transport import requests as google_requests
    from google.oauth2 import id_token
    if not settings.google_client_id:
        raise HTTPException(503, "서버에 GOOGLE_CLIENT_ID가 설정되지 않았습니다.")
    try:
        claims = id_token.verify_oauth2_token(token, google_requests.Request(), settings.google_client_id)
    except ValueError as exc:
        raise HTTPException(401, "구글 로그인을 확인하지 못했습니다.") from exc
    if not claims.get("email_verified"):
        raise HTTPException(401, "확인된 이메일이 있는 구글 계정만 쓸 수 있습니다.")
    return claims


def _sign_in(sub: str, email: str, name: str) -> dict:
    """Finds or creates the user and opens a session. ADMIN_EMAIL's account owns the records
    kept before sign-in existed (user "default")."""
    with pool.connection() as conn:
        row = conn.execute("SELECT id FROM app_users WHERE google_sub = %s", (sub,)).fetchone()
        if row:
            user = row["id"]
        elif settings.admin_email and email.lower() == settings.admin_email.lower() and not conn.execute(
                "SELECT 1 FROM app_users WHERE id = %s AND google_sub IS NOT NULL", (ADMIN_USER,)).fetchone():
            user = ADMIN_USER
            conn.execute("UPDATE app_users SET google_sub = %s WHERE id = %s", (sub, user))
        else:
            # Two devices signing in for the first time at once: the second takes the row the first created.
            conn.execute("INSERT INTO app_users(id, google_sub) VALUES (%s, %s) ON CONFLICT (google_sub) DO NOTHING",
                         ("u_" + secrets.token_hex(8), sub))
            user = conn.execute("SELECT id FROM app_users WHERE google_sub = %s", (sub,)).fetchone()["id"]
        conn.execute("UPDATE app_users SET email = %s, name = %s, last_login_at = now() WHERE id = %s",
                     (email, name, user))
        token = create_session(conn, user)
        conn.commit()
    return {"token": token, "user": {"id": user, "email": email, "name": name}}


@router.post("/google")
async def google_login(body: GoogleLogin):
    claims = await run_in_threadpool(_verify_google, body.id_token)
    return await run_in_threadpool(_sign_in, "google:" + claims["sub"], claims.get("email", ""), claims.get("name", ""))


@router.post("/dev")
async def dev_login(body: DevLogin):
    """Emulators without Google Play services. Off unless ALLOW_DEV_LOGIN=true; never enable in production."""
    if not settings.allow_dev_login:
        raise HTTPException(404, "Not Found")
    email = body.email.strip().lower()
    if "@" not in email:
        raise HTTPException(400, "이메일 형식이 아닙니다.")
    return await run_in_threadpool(_sign_in, "dev:" + email, email, email.split("@")[0])


@router.get("/me", dependencies=[Depends(require_user)])
def me():
    with pool.connection() as conn:
        row = conn.execute("SELECT id, email, name FROM app_users WHERE id = %s", (user_id(),)).fetchone()
    return row or {"id": user_id(), "email": "", "name": ""}


@router.post("/logout", dependencies=[Depends(require_user)])
def logout(authorization: str = Header("", alias="Authorization")):
    token = authorization.partition(" ")[2].strip()
    if token:
        with pool.connection() as conn:
            conn.execute("DELETE FROM app_sessions WHERE token_hash = %s", (hash_token(token),))
            conn.commit()
    return {"ok": True}


@router.delete("/me", dependencies=[Depends(require_user)])
def delete_account():
    """Deletes every record, saved photo, recording and linked account of the signed-in user."""
    user = user_id()
    if user == ADMIN_USER:
        raise HTTPException(400, "기존 기록을 가진 관리자 계정은 앱에서 지울 수 없습니다.")
    with get_conn() as conn:  # row level security scopes these to the user
        for table in _USER_TABLES:
            conn.execute(f"DELETE FROM {table}")
        conn.execute("DELETE FROM user_integrations WHERE user_id = %s", (user,))
        # Saved replies to retried writes hold copies of the saved records (owner as in idempotency.py).
        conn.execute("DELETE FROM request_receipts WHERE owner = %s",  # non-admin owner form
                     (hashlib.sha256(b"user:" + user.encode()).hexdigest(),))
        conn.execute("DELETE FROM app_users WHERE id = %s", (user,))  # sessions cascade
        conn.commit()
    # Folders are derived from the user id, never from record metadata (which the user can edit).
    from .meetings import _RECORDING_DIR
    from .memory import _IMAGE_DIR
    remove_user_files(_IMAGE_DIR, user)
    remove_user_files(_RECORDING_DIR, user)
    return {"ok": True}
