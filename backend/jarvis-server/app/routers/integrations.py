import base64
import hashlib
import hmac
import json
import time
import urllib.parse
import urllib.request
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import HTMLResponse, RedirectResponse
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..config import settings
from ..db import get_conn


router = APIRouter(
    prefix="/integrations",
    tags=["integrations"],
)


_SLACK_USER_SCOPES = (
    "search:read.public", "search:read.private", "search:read.im", "search:read.mpim", "search:read.users",
    "channels:history", "groups:history", "im:history", "mpim:history",
    "channels:read", "groups:read", "users:read", "chat:write",
)
_PROVIDERS = ("google", "slack", "slack_user", "notion")


def _form_post(url: str, data: dict[str, Any], headers: Optional[dict[str, str]] = None) -> dict[str, Any]:
    encoded = urllib.parse.urlencode(data).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=encoded,
        headers={"Content-Type": "application/x-www-form-urlencoded", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = response.read().decode("utf-8")
    return json.loads(payload) if payload else {}


def _upsert_integration(
    *,
    user_id: str,
    provider: str,
    access_token: str,
    refresh_token: Optional[str] = None,
    scopes: Optional[list[str]] = None,
    metadata: Optional[dict[str, Any]] = None,
) -> None:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO user_integrations
                    (user_id, provider, access_token_encrypted,
                     refresh_token_encrypted, scopes, metadata)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (user_id, provider)
                DO UPDATE SET
                    access_token_encrypted = EXCLUDED.access_token_encrypted,
                    refresh_token_encrypted = COALESCE(EXCLUDED.refresh_token_encrypted, user_integrations.refresh_token_encrypted),
                    scopes = EXCLUDED.scopes,
                    metadata = user_integrations.metadata || EXCLUDED.metadata,
                    updated_at = now()
                """,
                (
                    user_id,
                    provider,
                    access_token,
                    refresh_token,
                    scopes or [],
                    Jsonb(metadata or {}),
                ),
            )
        conn.commit()


@router.get("/status", dependencies=[Depends(require_api_key)])
def status(user_id: str = "default"):
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT provider, scopes, updated_at
                FROM user_integrations
                WHERE user_id = %s
                """,
                (user_id,),
            )
            rows = cur.fetchall()
    by_provider = {row["provider"]: row for row in rows}
    return {
        provider: {
            "connected": provider in by_provider,
            "scopes": by_provider.get(provider, {}).get("scopes", []),
            "updated_at": (
                by_provider[provider]["updated_at"].isoformat()
                if provider in by_provider and by_provider[provider]["updated_at"]
                else None
            ),
        }
        for provider in _PROVIDERS
    }


# 계정 연결 주소는 공개돼 있어서, 아무나 자기 계정을 Jarvis 에 연결하지 못하게 서명한 state 를 쓴다.
# /integrations/{provider}/link (API 키 필요) 가 10분짜리 서명 주소를 만들어 준다.
_STATE_TTL_S = 600


def _sign(text: str) -> str:
    return hmac.new(settings.api_key.encode(), text.encode(), hashlib.sha256).hexdigest()[:32]


def _make_state(provider: str, user_id: str) -> str:
    body = f"{provider}.{user_id}.{int(time.time()) + _STATE_TTL_S}"
    return f"{body}.{_sign(body)}"


def _check_state(provider: str, state: str) -> str:
    try:
        body, sig = state.rsplit(".", 1)
        state_provider, user_id, expires = body.split(".")
    except ValueError:
        raise HTTPException(400, "잘못된 연결 요청입니다. 연결 주소를 새로 받아 주세요.")
    if not hmac.compare_digest(sig, _sign(body)) or state_provider != provider.split("_")[0] or int(expires) < time.time():
        raise HTTPException(400, "연결 주소가 만료되었거나 올바르지 않습니다. 새로 받아 주세요.")
    return user_id


@router.get("/{provider}/link", dependencies=[Depends(require_api_key)])
def link(provider: str, user_id: str = "default"):
    if provider not in ("google", "slack", "notion"):
        raise HTTPException(404, "unknown integration provider")
    if "." in user_id:
        raise HTTPException(400, "user_id 에 마침표를 쓸 수 없습니다.")
    query = urllib.parse.urlencode({"state": _make_state(provider, user_id)})
    return {"url": f"{settings.public_base_url}/integrations/{provider}/connect?{query}", "expires_in": _STATE_TTL_S}


@router.get("/{provider}/connect")
def connect(provider: str, state: str = Query(...)):
    _check_state(provider, state)
    if provider == "google":
        if not settings.google_client_id or not settings.google_redirect_uri:
            raise HTTPException(400, "GOOGLE_CLIENT_ID and GOOGLE_REDIRECT_URI are required")
        params = {
            "client_id": settings.google_client_id,
            "redirect_uri": settings.google_redirect_uri,
            "response_type": "code",
            "scope": "https://www.googleapis.com/auth/calendar.events",
            "access_type": "offline",
            "prompt": "consent",
            "state": state,
        }
        return RedirectResponse("https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode(params))

    if provider == "slack":
        if not settings.slack_client_id or not settings.slack_redirect_uri:
            raise HTTPException(400, "SLACK_CLIENT_ID and SLACK_REDIRECT_URI are required")
        params = {
            "client_id": settings.slack_client_id,
            "redirect_uri": settings.slack_redirect_uri,
            "scope": "chat:write,channels:read,groups:read,im:write",
            # 슬랙 MCP 서버는 사용자 토큰으로만 쓴다(docs.slack.dev/ai/slack-mcp-server).
            "user_scope": ",".join(_SLACK_USER_SCOPES),
            "state": state,
        }
        return RedirectResponse("https://slack.com/oauth/v2/authorize?" + urllib.parse.urlencode(params))

    if provider == "notion":
        if not settings.notion_client_id or not settings.notion_redirect_uri:
            raise HTTPException(400, "NOTION_CLIENT_ID and NOTION_REDIRECT_URI are required")
        params = {
            "client_id": settings.notion_client_id,
            "redirect_uri": settings.notion_redirect_uri,
            "response_type": "code",
            "owner": "user",
            "state": state,
        }
        return RedirectResponse("https://api.notion.com/v1/oauth/authorize?" + urllib.parse.urlencode(params))

    raise HTTPException(404, "unknown integration provider")


@router.get("/{provider}/callback")
def callback(provider: str, code: str = Query(...), state: str = Query(...)):
    user_id = _check_state(provider, state)
    if provider == "google":
        data = _form_post(
            "https://oauth2.googleapis.com/token",
            {
                "client_id": settings.google_client_id,
                "client_secret": settings.google_client_secret,
                "code": code,
                "grant_type": "authorization_code",
                "redirect_uri": settings.google_redirect_uri,
            },
        )
        if "access_token" not in data:
            raise HTTPException(400, f"google token exchange failed: {data}")
        _upsert_integration(
            user_id=user_id,
            provider="google",
            access_token=data["access_token"],
            refresh_token=data.get("refresh_token"),
            scopes=(data.get("scope") or "").split(),
            metadata={"token_type": data.get("token_type"), "expires_in": data.get("expires_in"),
                      "expires_at": time.time() + float(data.get("expires_in") or 3600)},
        )
        return _connected_html("Google Calendar")

    if provider == "slack":
        data = _form_post(
            "https://slack.com/api/oauth.v2.access",
            {
                "client_id": settings.slack_client_id,
                "client_secret": settings.slack_client_secret,
                "code": code,
                "redirect_uri": settings.slack_redirect_uri,
            },
        )
        if not data.get("ok") or not data.get("access_token"):
            raise HTTPException(400, f"slack token exchange failed: {data}")
        authed = data.get("authed_user") or {}
        _upsert_integration(
            user_id=user_id,
            provider="slack",
            access_token=data["access_token"],
            scopes=(data.get("scope") or "").split(","),
            metadata={"team": data.get("team"), "authed_user": {"id": authed.get("id")}},
        )
        if authed.get("access_token"):
            _upsert_integration(
                user_id=user_id,
                provider="slack_user",
                access_token=authed["access_token"],
                scopes=(authed.get("scope") or "").split(","),
                metadata={"team": data.get("team"), "user_id": authed.get("id")},
            )
        return _connected_html("Slack")

    if provider == "notion":
        if not settings.notion_client_id or not settings.notion_client_secret:
            raise HTTPException(400, "NOTION_CLIENT_ID and NOTION_CLIENT_SECRET are required")
        basic = base64.b64encode(
            f"{settings.notion_client_id}:{settings.notion_client_secret}".encode("utf-8")
        ).decode("ascii")
        data = _form_post(
            "https://api.notion.com/v1/oauth/token",
            {
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": settings.notion_redirect_uri,
            },
            headers={"Authorization": f"Basic {basic}"},
        )
        if "access_token" not in data:
            raise HTTPException(400, f"notion token exchange failed: {data}")
        _upsert_integration(
            user_id=user_id,
            provider="notion",
            access_token=data["access_token"],
            metadata={"workspace_name": data.get("workspace_name"), "owner": data.get("owner")},
        )
        return _connected_html("Notion")

    raise HTTPException(404, "unknown integration provider")


def _connected_html(provider_name: str) -> HTMLResponse:
    return HTMLResponse(
        f"""
        <html>
          <head><title>Jarvis {provider_name} Connected</title></head>
          <body style="font-family: sans-serif; padding: 24px;">
            <h2>{provider_name} 연결 완료</h2>
            <p>Jarvis 앱으로 돌아가 테스트를 계속하세요.</p>
          </body>
        </html>
        """
    )
