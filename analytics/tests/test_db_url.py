from __future__ import annotations

from psycopg.conninfo import conninfo_to_dict

from whenioff_analytics.io.db import to_conninfo


def test_backend_style_jdbc_url_becomes_a_libpq_conninfo() -> None:
    parsed = conninfo_to_dict(to_conninfo("jdbc:postgresql://localhost:5432/when_i_off", "wio", "secret"))
    assert parsed["host"] == "localhost"
    assert parsed["port"] == "5432"
    assert parsed["dbname"] == "when_i_off"
    assert parsed["user"] == "wio"
    assert parsed["password"] == "secret"


def test_plain_postgresql_url_also_works() -> None:
    parsed = conninfo_to_dict(to_conninfo("postgresql://db:5432/when_i_off_test", "wio", "wio"))
    assert parsed["host"] == "db"
    assert parsed["dbname"] == "when_i_off_test"
