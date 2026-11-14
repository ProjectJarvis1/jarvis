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
_LIVE_CHUNK_S = 60  # 대화 시간을 한 번에 떼어 두는 양(초)
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
    # 오늘 쓸 수 있는 대화 시간. 1분씩 미리 떼어 두며 대화하고(동시에 연 대화도 같은 한도를 나눠 쓴다),
    # 더 뗄 수 없으면 그때 끊는다. 날짜가 바뀌면 새 날짜의 한도에서 뗀다. 다 썼으면 처음부터 연결하지 않는다.
    from .. import usage
    from ..auth import current_user
    current_user.set(user)  # 사용량 표는 행 보안을 쓰므로 이 연결의 사용자를 알려 준다
    loop = asyncio.get_running_loop()
    held = {"amount": 0, "day": None, "since": loop.time()}  # 지금 떼어 둔 양과 그 날짜, 뗀 시각

    async def hold_next() -> bool:
        try:
            amount, day = await run_in_threadpool(usage.reserve, user, usage.LIVE_SECONDS, _LIVE_CHUNK_S)
        except Exception:
            amount, day = _LIVE_CHUNK_S, None  # 사용량을 못 읽어도 대화는 막지 않는다
        held.update(amount=amount, day=day, since=loop.time())
        return amount > 0

    if not await hold_next():
        await ws.close(code=usage.LIVE_CLOSE_CODE, reason="daily limit")
        return
    over_limit = False

    reserving: dict[str, asyncio.Task | None] = {"task": None}

    async def budget():
        while True:
            await asyncio.sleep(max(1, held["amount"]))
            # 떼어 두는 중에 대화가 끝나도 그 결과가 held 에 남도록 따로 돌리고, 끝날 때 기다린다.
            reserving["task"] = asyncio.create_task(hold_next())
            if not await asyncio.shield(reserving["task"]):
                return  # 한도를 다 썼다

    import websockets
    watch: asyncio.Task | None = None
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

            watch = asyncio.create_task(budget())
            tasks = [asyncio.create_task(app_to_gemini()), asyncio.create_task(gemini_to_app()), watch]
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            if watch in done:
                over_limit = True  # 남은 시간을 다 썼다
                done.discard(watch)
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
    finally:
        if watch is not None and not watch.done():
            watch.cancel()  # 대화가 끝났으면 더 떼어 두지 않는다
    if reserving["task"] is not None and not reserving["task"].done():
        try:
            await reserving["task"]  # 떼어 두던 몫도 held 에 반영된 뒤에 정산한다
        except Exception:
            pass
    # 마지막으로 떼어 둔 양 가운데 쓰지 않은 만큼 그 날짜로 돌려준다.
    unused = int(held["amount"] - (loop.time() - held["since"]))
    if unused > 0 and held["day"] is not None:
        await run_in_threadpool(usage.refund, user, usage.LIVE_SECONDS, unused, held["day"])
    if over_limit:
        close, reason = usage.LIVE_CLOSE_CODE, "daily limit"
    try:
        # Pass the gateway's close code on, so the app's existing handling of quota/setup errors still works.
        await ws.close(code=close if close and close not in (1005, 1006) else 1000, reason=reason[:120])
    except Exception:  # the app already hung up
        pass
