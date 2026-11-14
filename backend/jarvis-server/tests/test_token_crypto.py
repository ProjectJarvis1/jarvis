"""연결 토큰 암호화 시험: 왕복, 예전 평문 줄을 읽고 다시 쓸 때 암호화, 틀린 키, 키 없는 운영, 일괄 변환 스크립트."""
import json
import os
import subprocess
import sys
from pathlib import Path
from uuid import uuid4

import pytest
from cryptography.fernet import Fernet

os.environ.setdefault("DATABASE_URL", os.getenv("TEST_DATABASE_URL") or "postgresql://unused@127.0.0.1:1/unused")
os.environ.setdefault("API_KEY", "test-only-assistant-key")
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app import mcp_tools as t  # noqa: E402
from app import token_crypto as c  # noqa: E402
from app.routers import integrations as integ  # noqa: E402

KEY = Fernet.generate_key().decode()


@pytest.fixture()
def key(monkeypatch):
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", KEY)
    monkeypatch.delenv("ADMIN_EMAIL", raising=False)
    return KEY


class FakeConn:
    def __init__(self, row=None):
        self.row, self.sql = row, []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def cursor(self):
        return self

    def execute(self, sql, params=()):
        self.sql.append((sql, params))
        return self

    def fetchone(self):
        return self.row

    def commit(self):
        pass


def test_round_trip_and_plaintext_passthrough(key):
    secret = "ya29.example-access-token"
    stored = c.encrypt(secret)
    assert stored.startswith("enc1:") and secret not in stored
    assert c.decrypt(stored) == secret
    assert c.encrypt(stored) == stored  # 두 번 암호화하지 않는다
    assert c.decrypt("plain-old-token") == "plain-old-token"  # 도입 전 평문 줄
    assert c.encrypt(None) is None and c.decrypt(None) is None


def test_wrong_key_fails_loudly_and_tool_sees_no_token(key, monkeypatch):
    stored = c.encrypt("tok")
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode())
    with pytest.raises(c.TokenKeyError):
        c.decrypt(stored)
    monkeypatch.setattr(t, "get_conn", lambda: FakeConn({"access_token_encrypted": stored,
                                                          "refresh_token_encrypted": None, "metadata": {}}))
    assert t.integration_token("slack_user", "u1") is None  # 틀린 키로 푼 쓰레기 값을 쓰지 않는다
    monkeypatch.delenv("TOKEN_ENCRYPTION_KEY")
    with pytest.raises(c.TokenKeyError):
        c.decrypt(stored)


def test_saving_a_connection_encrypts_tokens(key, monkeypatch):
    conn = FakeConn()
    monkeypatch.setattr(integ, "get_conn", lambda: conn)
    integ._upsert_integration(user_id="u1", provider="slack", access_token="xoxb-1", refresh_token="r-1")
    params = conn.sql[-1][1]
    assert c.is_encrypted(params[2]) and c.is_encrypted(params[3])
    assert c.decrypt(params[2]) == "xoxb-1" and c.decrypt(params[3]) == "r-1"


def test_plain_google_row_is_read_then_rewritten_encrypted(key, monkeypatch):
    # 예전 평문 줄: 만료된 접근 토큰 + 평문 refresh token. 읽어서 쓰고, 새 접근 토큰은 암호화해 저장한다.
    conn = FakeConn({"access_token_encrypted": "old-plain", "refresh_token_encrypted": "refresh-plain",
                     "metadata": {"expires_at": 0}})
    monkeypatch.setattr(t, "get_conn", lambda: conn)
    sent = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return json.dumps({"access_token": "fresh-token", "expires_in": 3600}).encode()

    def urlopen(request, timeout=0):
        sent["body"] = request.data.decode()
        return Response()

    monkeypatch.setattr(t.urllib.request, "urlopen", urlopen)
    assert t._integration_token("u1", "google") == "fresh-token"
    assert "refresh_token=refresh-plain" in sent["body"]  # 평문 refresh token 을 그대로 썼다
    update = conn.sql[-1]
    assert update[0].strip().startswith("UPDATE user_integrations")
    assert c.is_encrypted(update[1][0]) and c.decrypt(update[1][0]) == "fresh-token"


def test_missing_key_in_production(monkeypatch):
    monkeypatch.delenv("TOKEN_ENCRYPTION_KEY", raising=False)
    monkeypatch.setenv("ADMIN_EMAIL", "admin@example.com")
    with pytest.raises(c.TokenKeyError):
        c.encrypt("tok")  # 운영에서는 평문으로 저장하지 않는다
    with pytest.raises(c.TokenKeyError):
        c.check_on_startup(None)  # 서버가 뜨지 않는다
    monkeypatch.delenv("ADMIN_EMAIL")
    assert c.encrypt("tok") == "tok"  # 개발에서는 지금처럼
    c.check_on_startup(None)


def test_startup_rejects_key_that_cannot_open_stored_tokens(key, monkeypatch):
    stored = c.encrypt("tok")
    c.check_on_startup(stored)
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode())
    monkeypatch.setenv("ADMIN_EMAIL", "admin@example.com")
    with pytest.raises(c.TokenKeyError):
        c.check_on_startup(stored)
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", "not-a-key")
    with pytest.raises(c.TokenKeyError):
        c.check_on_startup(None)


def test_plan_counts_only_plaintext(key):
    sys.path.insert(0, str(ROOT / "scripts"))
    import encrypt_tokens
    rows = [
        {"user_id": "a", "provider": "google", "access_token_encrypted": "p", "refresh_token_encrypted": c.encrypt("r"),
         "metadata": {}},
        {"user_id": "_jarvis", "provider": "notion_mcp_client", "access_token_encrypted": c.encrypt("cid"),
         "refresh_token_encrypted": None, "metadata": {"client_secret": "s", "redirect_uri": "x"}},
        {"user_id": "b", "provider": "slack", "access_token_encrypted": c.encrypt("x"), "refresh_token_encrypted": None,
         "metadata": {}},
    ]
    changes = encrypt_tokens.plan(rows)
    assert [(u, p, sorted(up)) for u, p, up in changes] == [("a", "google", ["access_token_encrypted"]),
                                                           ("_jarvis", "notion_mcp_client", ["metadata"])]
    assert c.decrypt(changes[1][2]["metadata"]["client_secret"]) == "s"
    assert changes[1][2]["metadata"]["redirect_uri"] == "x"


@pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="disposable TEST_DATABASE_URL required")
def test_script_dry_run_then_apply_on_real_db(key):
    import psycopg
    url = os.environ["TEST_DATABASE_URL"]
    user = f"enc-{uuid4()}"
    with psycopg.connect(url) as conn:
        conn.execute("""CREATE TABLE IF NOT EXISTS user_integrations (
            user_id text NOT NULL, provider text NOT NULL, access_token_encrypted text, refresh_token_encrypted text,
            scopes text[] NOT NULL DEFAULT '{}', metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
            created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (user_id, provider))""")
        conn.execute("INSERT INTO user_integrations (user_id, provider, access_token_encrypted, refresh_token_encrypted) "
                     "VALUES (%s, 'google', 'plain-a', 'plain-r')", (user,))
        conn.commit()
    env = {**os.environ, "DATABASE_URL": url, "TOKEN_ENCRYPTION_KEY": key}
    env.pop("ADMIN_EMAIL", None)
    run = lambda *a: subprocess.run([sys.executable, str(ROOT / "scripts/encrypt_tokens.py"), *a], env=env,
                                    capture_output=True, text=True, cwd=ROOT)
    try:
        dry = run()
        assert dry.returncode == 0 and "확인만" in dry.stdout and "plain-a" not in dry.stdout
        with psycopg.connect(url) as conn:
            assert conn.execute("SELECT access_token_encrypted FROM user_integrations WHERE user_id=%s",
                                (user,)).fetchone()[0] == "plain-a"
        applied = run("--apply")
        assert applied.returncode == 0, applied.stdout + applied.stderr
        with psycopg.connect(url) as conn:
            access, refresh = conn.execute("SELECT access_token_encrypted, refresh_token_encrypted FROM user_integrations "
                                           "WHERE user_id=%s", (user,)).fetchone()
        assert c.decrypt(access) == "plain-a" and c.decrypt(refresh) == "plain-r"
        again = run("--apply")
        assert f"{user} / google" not in again.stdout  # 이미 바꾼 줄은 건드리지 않는다
        wrong = run()
        env["TOKEN_ENCRYPTION_KEY"] = Fernet.generate_key().decode()
        refused = run("--apply")
        assert refused.returncode == 1 and "멈춤" in refused.stdout
        assert wrong.returncode == 0
    finally:
        with psycopg.connect(url) as conn:
            conn.execute("DELETE FROM user_integrations WHERE user_id = %s", (user,))
            conn.commit()
