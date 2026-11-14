"""외부 서비스 연결 토큰(구글, 슬랙, 노션)을 DB 에 저장할 때 쓰는 암호화.

- 키는 DB 밖의 환경변수 TOKEN_ENCRYPTION_KEY(Fernet 키)에서 읽는다. DB 를 통째로 빼 가도 토큰은 못 쓴다.
- 암호문은 "enc1:" 로 시작한다. 이 표시가 없는 값은 암호화 도입 전의 평문으로 보고 그대로 쓴다.
  평문 줄은 다음에 그 토큰을 저장할 때 암호화되고, 한 번에 바꾸려면 scripts/encrypt_tokens.py 를 쓴다.
- 운영(ADMIN_EMAIL 이 설정된 서버)에서는 키가 없거나 틀리면 서버가 뜨지 않는다(check_on_startup).
  개발과 시험에서는 키가 없으면 지금처럼 평문으로 저장한다.
"""
import logging
import os
from functools import lru_cache
from typing import Any, Optional

from cryptography.fernet import Fernet, InvalidToken

PREFIX = "enc1:"
log = logging.getLogger("jarvis.tokens")


class TokenKeyError(RuntimeError):
    """키가 없거나 틀려서 토큰을 암호화하거나 풀 수 없음."""


def _key() -> str:
    return os.getenv("TOKEN_ENCRYPTION_KEY", "").strip()


def production() -> bool:
    """관리자 계정(ADMIN_EMAIL)을 정한 서버를 운영으로 본다."""
    return bool(os.getenv("ADMIN_EMAIL", "").strip())


@lru_cache(maxsize=4)
def _fernet(key: str) -> Fernet:
    try:
        return Fernet(key.encode())
    except (ValueError, TypeError) as exc:
        raise TokenKeyError("TOKEN_ENCRYPTION_KEY 형식이 올바르지 않습니다. .env.example 의 생성 방법을 보세요.") from exc


def enabled() -> bool:
    return bool(_key())


def is_encrypted(value: Any) -> bool:
    return isinstance(value, str) and value.startswith(PREFIX)


def encrypt(value: Optional[str]) -> Optional[str]:
    """저장 직전에 부른다. 빈 값과 이미 암호화된 값은 그대로 둔다."""
    if value is None or value == "" or is_encrypted(value):
        return value
    key = _key()
    if not key:
        if production():
            raise TokenKeyError("TOKEN_ENCRYPTION_KEY 가 없어 연결 토큰을 저장하지 않았습니다.")
        return value
    return PREFIX + _fernet(key).encrypt(value.encode()).decode()


def decrypt(value: Optional[str]) -> Optional[str]:
    """읽은 직후에 부른다. 평문(예전 줄)은 그대로 돌려준다. 키가 없거나 틀리면 TokenKeyError."""
    if not is_encrypted(value):
        return value
    key = _key()
    if not key:
        raise TokenKeyError("암호화된 연결 토큰이 있는데 TOKEN_ENCRYPTION_KEY 가 없습니다.")
    try:
        return _fernet(key).decrypt(value[len(PREFIX):].encode()).decode()
    except InvalidToken as exc:
        raise TokenKeyError("TOKEN_ENCRYPTION_KEY 가 토큰을 암호화한 키와 다릅니다.") from exc


def check_on_startup(sample: Optional[str]) -> None:
    """서버 시작 때 부른다. sample 은 DB 의 암호문 하나(없으면 None).
    운영에서 키가 없거나 형식이 틀리거나 DB 의 암호문을 못 풀면 시작을 멈춘다."""
    key = _key()
    try:
        if key:
            _fernet(key)
        if sample is not None:
            decrypt(sample)
        if not key and production():
            raise TokenKeyError("운영 서버(ADMIN_EMAIL 설정됨)에는 TOKEN_ENCRYPTION_KEY 가 필요합니다.")
    except TokenKeyError as exc:
        if production():
            raise
        log.warning("연결 토큰 암호화 설정 문제(개발 환경이라 계속): %s", exc)
