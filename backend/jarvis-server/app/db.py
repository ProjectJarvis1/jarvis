from contextlib import contextmanager
from typing import Iterator

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool
from pgvector.psycopg import register_vector

from .config import settings


def _configure(conn) -> None:
    register_vector(conn)


pool = ConnectionPool(
    conninfo=settings.database_url,
    min_size=1,
    max_size=10,
    open=False,
    configure=_configure,
    kwargs={"row_factory": dict_row},
)


def open_pool() -> None:
    pool.open()
    pool.wait()


def close_pool() -> None:
    pool.close()


def ensure_schema() -> None:
    from pathlib import Path
    from .idempotency import DDL
    root = Path(__file__).resolve().parents[1]
    steps = [("001_base", (root / "schema.sql").read_text()),
             ("001_assistant", DDL)]
    steps += [(path.stem, path.read_text()) for path in sorted((root / "migrations").glob("*.sql"))]
    with pool.connection() as conn:
        # All application instances apply startup migrations under one transaction lock.
        conn.execute("RESET ROLE")  # a reused connection may still be jarvis_app, which cannot run DDL
        conn.execute("SELECT pg_advisory_xact_lock(728104602)")
        conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
        applied = {row["version"] for row in conn.execute("SELECT version FROM schema_migrations").fetchall()}
        for version, sql in steps:
            if version not in applied:
                conn.execute(sql)
                conn.execute("INSERT INTO schema_migrations(version) VALUES(%s)", (version,))
        conn.commit()


def _bind_user(conn) -> None:
    """Row level security reads app.user_id. Set on every checkout: pooled connections keep the
    previous request's value otherwise. No user means '' and therefore no rows."""
    from .auth import current_user
    # The owner role may bypass row level security, so requests run as jarvis_app (migration 005).
    user = current_user.get() or ""
    # Every transaction of a user holds a shared per-user lock until it ends; account deletion takes it
    # exclusively ([lock_user_exclusively]), so a save that started before the deletion finishes first
    # (and is deleted with the rest) and one that starts after it sees the user gone and is refused.
    conn.execute("SELECT set_config('role', 'jarvis_app', false), set_config('app.user_id', %s, false), "
                 "pg_advisory_xact_lock_shared(hashtextextended('jarvis-user:' || %s, 0))",
                 (user, user))


def lock_user_exclusively(conn, user: str) -> None:
    """Waits for the user's running transactions and blocks new ones until this transaction ends."""
    conn.execute("SELECT pg_advisory_xact_lock(hashtextextended('jarvis-user:' || %s, 0))", (user,))


@contextmanager
def get_conn() -> Iterator:
    from .idempotency import transaction_connection
    current = transaction_connection.get()
    if current is not None:
        _bind_user(current)
        yield current
        return
    with pool.connection() as conn:
        _bind_user(conn)
        yield conn
