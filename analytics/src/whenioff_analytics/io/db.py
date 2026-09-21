"""psycopg 연결. 스키마는 Flyway가 소유하므로 analytics는 DDL을 만들지 않는다."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import psycopg
from psycopg.conninfo import make_conninfo

from whenioff_analytics.settings import Settings

Connection = psycopg.Connection[Any]

JDBC_PREFIX = "jdbc:"


def to_conninfo(db_url: str, user: str, password: str) -> str:
    """backend와 같은 `WIO_DB_URL`(JDBC 형식)을 libpq 연결 문자열로 바꾼다."""
    url = db_url[len(JDBC_PREFIX) :] if db_url.startswith(JDBC_PREFIX) else db_url
    return make_conninfo(url, user=user, password=password)


@contextmanager
def connect(settings: Settings | None = None) -> Iterator[Connection]:
    resolved = settings if settings is not None else Settings()
    with psycopg.connect(to_conninfo(resolved.db_url, resolved.db_user, resolved.db_password)) as conn:
        yield conn
