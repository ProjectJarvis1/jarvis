"""Gemini Live relay. The app opens this socket with its session token; the server opens the
gateway socket with the key, which therefore never ships inside the app. Frames pass through
unchanged in both directions."""
import asyncio
import logging

from fastapi import APIRouter, WebSocket
from starlette.concurrency import run_in_threadpool

from ..auth import resolve_user
from ..config import settings

log = logging.getLogger("jarvis.gemini_relay")
router = APIRouter(tags=["gemini"])


@router.websocket("/live/gemini")
async def gemini_live(ws: WebSocket):
    user = await run_in_threadpool(resolve_user, ws.headers.get("authorization", ""), "")
    if user is None:
        await ws.close(code=4401, reason="login required")
        return
    # Refusing before accept answers the handshake with HTTP 403, which the app reads as "signed out";
    # a server-side setup problem is therefore reported after accept, as a normal close.
    await ws.accept()
    if not (settings.gemini_live_url and settings.gemini_live_key):
        await ws.close(code=1011, reason="relay not configured")
        return
    import websockets
    try:
        async with websockets.connect(settings.gemini_live_url, max_size=None, open_timeout=15,
                                      additional_headers={"x-goog-api-key": settings.gemini_live_key}) as upstream:
            async def app_to_gemini():
                while True:
                    message = await ws.receive()
                    if message["type"] == "websocket.disconnect":
                        return
                    if message.get("bytes") is not None:
                        await upstream.send(message["bytes"])
                    elif message.get("text") is not None:
                        await upstream.send(message["text"])

            async def gemini_to_app():
                async for frame in upstream:
                    if isinstance(frame, bytes):
                        await ws.send_bytes(frame)
                    else:
                        await ws.send_text(frame)

            tasks = [asyncio.create_task(app_to_gemini()), asyncio.create_task(gemini_to_app())]
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            for task in done:
                if task.exception() and not isinstance(task.exception(), websockets.ConnectionClosed):
                    log.warning("Gemini 중계 끊김: %s", type(task.exception()).__name__)
            close = upstream.close_code
            reason = upstream.close_reason or ""
    except Exception as exc:  # gateway down or refused
        log.warning("Gemini 게이트웨이 연결 실패: %s", type(exc).__name__)
        close, reason = 1011, "gateway unavailable"
    try:
        # Pass the gateway's close code on, so the app's existing handling of quota/setup errors still works.
        await ws.close(code=close if close and close not in (1005, 1006) else 1000, reason=reason[:120])
    except Exception:  # the app already hung up
        pass
