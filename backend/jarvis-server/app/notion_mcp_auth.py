"""노션 공식 원격 MCP(mcp.notion.com) 계정 연결.

노션 원격 MCP 는 클라이언트 자동 등록(RFC 7591)과 PKCE 를 지원한다. 그래서 노션 개발자 페이지에서
연동을 만들 필요 없이, 앱의 '연결' 버튼 → 노션 로그인 → 페이지 고르기만 하면 된다.

- 등록한 클라이언트 정보는 user_integrations 의 provider='notion_mcp_client' 줄에 한 번만 저장해 다시 쓴다.
- PKCE 검증값은 서명된 state 에서 서버 키로 다시 계산한다(주소로 내보내지 않고, 따로 저장하지 않는다).
- 토큰은 provider='notion_mcp' 줄에 저장하고, 만료 1분 전부터 refresh token 으로 새로 받는다.
"""
import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

from psycopg.types.json import Jsonb

from .config import settings
from .db import get_conn

ISSUER = "https://mcp.notion.com"
RESOURCE = "https://mcp.notion.com/mcp"
_CLIENT_ROW = ("_jarvis", "notion_mcp_client")


def redirect_uri() -> str:
    return f"{settings.public_base_url}/integrations/notion/callback"


def _verifier(state: str) -> str:
    digest = hmac.new(settings.api_key.encode(), f"notion-pkce.{state}".encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest + digest).decode().rstrip("=")  # 86자, PKCE 허용 길이(43~128)


def _challenge(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")


def _post(url: str, data: dict[str, Any], as_json: bool = False) -> dict[str, Any]:
    body = json.dumps(data).encode() if as_json else urllib.parse.urlencode(data).encode()
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json" if as_json else "application/x-www-form-urlencoded",
        "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode(errors="replace")[:300]
        raise RuntimeError(f"노션 응답 {exc.code}: {detail}") from exc


def _stored_client() -> Optional[dict[str, Any]]:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT access_token_encrypted, metadata FROM user_integrations WHERE user_id = %s AND provider = %s",
                        _CLIENT_ROW)
            row = cur.fetchone()
    if not row or (row["metadata"] or {}).get("redirect_uri") != redirect_uri():
        return None
    return {"client_id": row["access_token_encrypted"], "client_secret": (row["metadata"] or {}).get("client_secret")}


def _client() -> dict[str, Any]:
    stored = _stored_client()
    if stored:
        return stored
    data = _post(f"{ISSUER}/register", {
        "client_name": "Jarvis",
        "redirect_uris": [redirect_uri()],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
    }, as_json=True)
    if not data.get("client_id"):
        raise RuntimeError(f"노션 클라이언트 등록 실패: {str(data)[:200]}")
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO user_integrations (user_id, provider, access_token_encrypted, scopes, metadata)
                VALUES (%s, %s, %s, '{}', %s)
                ON CONFLICT (user_id, provider) DO UPDATE SET
                    access_token_encrypted = EXCLUDED.access_token_encrypted, metadata = EXCLUDED.metadata, updated_at = now()
                """,
                (*_CLIENT_ROW, data["client_id"],
                 Jsonb({"client_secret": data.get("client_secret"), "redirect_uri": redirect_uri()})),
            )
        conn.commit()
    return {"client_id": data["client_id"], "client_secret": data.get("client_secret")}


def _client_auth(client: dict[str, Any]) -> dict[str, str]:
    out = {"client_id": client["client_id"]}
    if client.get("client_secret"):
        out["client_secret"] = client["client_secret"]
    return out


def authorize_url(state: str) -> str:
    client = _client()
    params = {
        "response_type": "code",
        "client_id": client["client_id"],
        "redirect_uri": redirect_uri(),
        "state": state,
        "code_challenge": _challenge(_verifier(state)),
        "code_challenge_method": "S256",
        "resource": RESOURCE,
    }
    return f"{ISSUER}/authorize?" + urllib.parse.urlencode(params)


def _save_tokens(user_id: str, data: dict[str, Any]) -> None:
    expires_at = time.time() + float(data.get("expires_in") or 3600)
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO user_integrations
                    (user_id, provider, access_token_encrypted, refresh_token_encrypted, scopes, metadata)
                VALUES (%s, 'notion_mcp', %s, %s, %s, %s)
                ON CONFLICT (user_id, provider) DO UPDATE SET
                    access_token_encrypted = EXCLUDED.access_token_encrypted,
                    refresh_token_encrypted = COALESCE(EXCLUDED.refresh_token_encrypted, user_integrations.refresh_token_encrypted),
                    scopes = EXCLUDED.scopes,
                    metadata = user_integrations.metadata || EXCLUDED.metadata,
                    updated_at = now()
                """,
                (user_id, data["access_token"], data.get("refresh_token"), (data.get("scope") or "").split(),
                 Jsonb({"expires_at": expires_at})),
            )
        conn.commit()


def finish(user_id: str, code: str, state: str) -> None:
    client = _client()
    data = _post(f"{ISSUER}/token", {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri(),
        "code_verifier": _verifier(state),
        "resource": RESOURCE,
        **_client_auth(client),
    })
    if not data.get("access_token"):
        raise RuntimeError(f"노션 토큰 교환 실패: {str(data)[:200]}")
    _save_tokens(user_id, data)


def fresh_token(user_id: str, row: dict[str, Any]) -> Optional[str]:
    """저장된 노션 MCP 토큰. 만료가 가까우면 refresh token 으로 새로 받는다."""
    expires_at = float((row.get("metadata") or {}).get("expires_at") or 0)
    if expires_at - 60 > time.time() or not row.get("refresh_token_encrypted"):
        return row["access_token_encrypted"]
    client = _stored_client()
    if not client:
        return None
    data = _post(f"{ISSUER}/token", {
        "grant_type": "refresh_token",
        "refresh_token": row["refresh_token_encrypted"],
        "resource": RESOURCE,
        **_client_auth(client),
    })
    if not data.get("access_token"):
        return None
    _save_tokens(user_id, data)
    return data["access_token"]
