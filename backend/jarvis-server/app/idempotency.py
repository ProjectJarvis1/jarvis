"""Atomic DB writes + durable HTTP replay for explicitly keyed JSON mutations.

All get_conn() calls in the request share one transaction. Files/model calls are
not rolled back; this guarantees DB effects, not external provider side effects.
"""
import hashlib
import json
from contextvars import ContextVar

from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse

transaction_connection = ContextVar("transaction_connection", default=None)

DDL = """
CREATE TABLE IF NOT EXISTS request_receipts (
    owner text NOT NULL, request_key text NOT NULL, fingerprint text NOT NULL,
    status integer NOT NULL, headers jsonb NOT NULL, body bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(owner, request_key)
);
CREATE TABLE IF NOT EXISTS assistant_changes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), kind text NOT NULL, record_id uuid NOT NULL,
    before_data jsonb NOT NULL, after_data jsonb NOT NULL, state text NOT NULL DEFAULT 'preview',
    created_at timestamptz NOT NULL DEFAULT now()
);
"""


class TransactionView:
    def __init__(self, connection):
        self.connection = connection

    def __getattr__(self, name):
        return getattr(self.connection, name)

    def commit(self):
        # Existing handlers commit locally. The middleware owns the actual commit.
        pass


def eligible(method, path):
    return method == "POST" and (path in {
        "/people", "/meetings", "/memory/save", "/memory/life/save", "/needs",
        "/assistant/changes",
    } or (path.startswith("/assistant/changes/") and path.endswith(("/apply", "/undo"))))


class IdempotencyMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not eligible(scope["method"], scope["path"]):
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        key = headers.get(b"idempotency-key", b"").decode("ascii", errors="replace")
        if not key:
            return await self.app(scope, receive, send)
        if not 1 <= len(key) <= 100:
            return await JSONResponse({"detail": "Invalid idempotency key"}, 400)(scope, receive, send)
        # Authenticate before reading/storing potentially large bodies or replaying responses.
        from .config import settings
        import secrets
        if not secrets.compare_digest(headers.get(b"x-api-key", b""), settings.api_key.encode()):
            return await JSONResponse({"detail": "Invalid API key"}, 401)(scope, receive, send)
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            body.extend(message.get("body", b""))
            if len(body) > 20 * 1024 * 1024:
                return await JSONResponse({"detail": "Request too large"}, 413)(scope, receive, send)
            if not message.get("more_body", False):
                break
        owner = hashlib.sha256(settings.api_key.encode()).hexdigest()
        fingerprint = hashlib.sha256(scope["path"].encode() + b"\n" + body).hexdigest()
        from .db import pool
        from psycopg.types.json import Jsonb
        conn = await run_in_threadpool(pool.getconn)
        token = None
        try:
            def lookup():
                conn.execute("SET LOCAL lock_timeout = '10s'")
                conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (owner + key,))
                return conn.execute("SELECT * FROM request_receipts WHERE owner=%s AND request_key=%s", (owner, key)).fetchone()
            prior = await run_in_threadpool(lookup)
            if prior:
                if prior["fingerprint"] != fingerprint:
                    return await JSONResponse({"detail": "Key reused with different request"}, 409)(scope, receive, send)
                await send({"type": "http.response.start", "status": prior["status"],
                            "headers": [(k.encode(), v.encode()) for k, v in prior["headers"]]})
                return await send({"type": "http.response.body", "body": bytes(prior["body"])})
            token = transaction_connection.set(TransactionView(conn))
            messages = []
            delivered = False
            async def replay_receive():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {"type": "http.request", "body": bytes(body), "more_body": False}
                return await receive()
            async def capture(message):
                messages.append(message)
            await self.app(scope, replay_receive, capture)
            start = next(m for m in messages if m["type"] == "http.response.start")
            if 200 <= start["status"] < 300:
                start["headers"] = list(start["headers"]) + [(b"x-jarvis-idempotent", b"1")]
                result = b"".join(m.get("body", b"") for m in messages if m["type"] == "http.response.body")
                def commit():
                    conn.execute("INSERT INTO request_receipts(owner,request_key,fingerprint,status,headers,body) VALUES(%s,%s,%s,%s,%s,%s)",
                                 (owner, key, fingerprint, start["status"], Jsonb([(k.decode(), v.decode()) for k, v in start["headers"]]), result))
                    conn.commit()
                await run_in_threadpool(commit)
            else:
                await run_in_threadpool(conn.rollback)
            for message in messages:
                await send(message)
        finally:
            if token is not None:
                transaction_connection.reset(token)
            # Also releases the transaction lock on replay/conflict/error.
            await run_in_threadpool(conn.rollback)
            await run_in_threadpool(pool.putconn, conn)
