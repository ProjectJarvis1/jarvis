"""user_integrations 에 남은 평문 연결 토큰을 한 번에 암호화한다.

    python scripts/encrypt_tokens.py           # 몇 줄이 바뀌는지만 센다(아무것도 바꾸지 않음)
    python scripts/encrypt_tokens.py --apply   # 실제로 암호화해 저장한다(한 트랜잭션)

TOKEN_ENCRYPTION_KEY 가 .env 나 환경변수에 있어야 한다. 이미 암호화된 값은 건드리지 않으므로 여러 번 돌려도 된다.
토큰 값은 화면에 찍지 않는다.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import psycopg  # noqa: E402
from psycopg.rows import dict_row  # noqa: E402
from psycopg.types.json import Jsonb  # noqa: E402

from app import token_crypto  # noqa: E402
from app.config import settings  # noqa: E402


def plan(rows: list[dict]) -> list[tuple[str, str, dict]]:
    """(user_id, provider, 바꿀 칼럼) 목록. 평문이 남은 줄만."""
    changes = []
    for row in rows:
        update: dict = {}
        for column in ("access_token_encrypted", "refresh_token_encrypted"):
            value = row.get(column)
            if value and not token_crypto.is_encrypted(value):
                update[column] = token_crypto.encrypt(value)
        metadata = row.get("metadata") or {}
        secret = metadata.get("client_secret")
        if secret and not token_crypto.is_encrypted(secret):
            update["metadata"] = {**metadata, "client_secret": token_crypto.encrypt(secret)}
        if update:
            changes.append((row["user_id"], row["provider"], update))
    return changes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apply", action="store_true", help="실제로 바꿔 저장")
    args = parser.parse_args()
    if not token_crypto.enabled():
        print("TOKEN_ENCRYPTION_KEY 가 없습니다. 키를 먼저 .env 에 넣으세요.")
        return 2
    with psycopg.connect(settings.database_url, row_factory=dict_row) as conn:
        rows = conn.execute(
            "SELECT user_id, provider, access_token_encrypted, refresh_token_encrypted, metadata "
            "FROM user_integrations ORDER BY user_id, provider FOR UPDATE").fetchall()
        # 이미 암호화된 줄이 지금 키로 풀리는지 먼저 본다. 다른 키로 섞어 쓰지 않게.
        try:
            for row in rows:
                for value in (row["access_token_encrypted"], row["refresh_token_encrypted"],
                              (row["metadata"] or {}).get("client_secret")):
                    token_crypto.decrypt(value)
        except token_crypto.TokenKeyError as exc:
            print(f"멈춤: {exc} (아무것도 바꾸지 않았습니다)")
            return 1
        changes = plan(rows)
        print(f"전체 {len(rows)}줄 중 평문이 남은 줄 {len(changes)}개")
        for user_id, provider, update in changes:
            print(f"  {user_id} / {provider}: {', '.join(sorted(update))}")
        if not args.apply:
            print("확인만 했습니다. 바꾸려면 --apply 를 붙이세요.")
            conn.rollback()
            return 0
        for user_id, provider, update in changes:
            sets = ", ".join(f"{column} = %s" for column in update)
            values = [Jsonb(v) if column == "metadata" else v for column, v in update.items()]
            conn.execute(f"UPDATE user_integrations SET {sets} WHERE user_id = %s AND provider = %s",
                         (*values, user_id, provider))
        conn.commit()
        print(f"{len(changes)}줄을 암호화했습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
