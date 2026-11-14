"""앱이 강제 종료됐을 때 남긴 기록을 받는다. 외부 업체 없이 우리 서버에만 쌓는다.

받는 것은 스택, 앱 버전, 빌드 종류(glasses/phone), 기기 모델, OS 버전, 발생 시각뿐이다. 대화 내용이나
기록 내용은 앱이 보내지 않는다. 한 사람이 하루에 남길 수 있는 줄 수와 한 줄의 크기를 제한한다.
"""
from datetime import datetime, timedelta
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..auth import require_user
from ..auth import user_id as current_user_id
from ..db import get_conn

router = APIRouter(prefix="/client-errors", tags=["client-errors"], dependencies=[Depends(require_user)])

MAX_STACK = 16_000
MAX_PER_DAY = 50


class ClientError(BaseModel):
    stack: str = Field(min_length=1, max_length=200_000)
    happened_at: Optional[datetime] = None
    app_version: str = Field("", max_length=64)
    flavor: str = Field("", max_length=32)
    device: str = Field("", max_length=128)
    os_version: str = Field("", max_length=64)


@router.post("", status_code=202)
def report(body: ClientError):
    """같은 오류가 반복돼도 하루 MAX_PER_DAY 줄까지만 남긴다. 넘치면 받기만 하고 버린다(앱이 다시 보내지 않게 202)."""
    user = current_user_id()
    with get_conn() as conn:
        # 같은 사용자의 동시 보고가 하루 한도를 함께 넘지 않게 사용자별로 줄을 세운다.
        conn.execute("SELECT pg_advisory_xact_lock(hashtext('client_errors:' || %s))", (user,))
        count = conn.execute("SELECT count(*) AS n FROM client_errors WHERE user_id = %s AND received_at > %s",
                             (user, datetime.now().astimezone() - timedelta(days=1))).fetchone()["n"]
        if count >= MAX_PER_DAY:
            conn.rollback()
            return {"stored": False}
        conn.execute(
            "INSERT INTO client_errors(user_id, happened_at, app_version, flavor, device, os_version, stack) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s)",
            (user, body.happened_at, body.app_version, body.flavor, body.device, body.os_version,
             body.stack[:MAX_STACK]))
        conn.commit()
    return {"stored": True}
