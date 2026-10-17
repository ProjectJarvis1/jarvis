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
        conn.execute("SELECT pg_advisory_xact_lock(728104602)")
        conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
        applied = {row["version"] for row in conn.execute("SELECT version FROM schema_migrations").fetchall()}
        for version, sql in steps:
            if version not in applied:
                conn.execute(sql)
                conn.execute("INSERT INTO schema_migrations(version) VALUES(%s)", (version,))
        conn.commit()


@contextmanager
def get_conn() -> Iterator:
    from .idempotency import transaction_connection
    current = transaction_connection.get()
    if current is not None:
        yield current
        return
    with pool.connection() as conn:
        yield conn
