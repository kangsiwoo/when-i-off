"""구간(승차→하차) 방향 판정. backend `LegDirectionResolver`와 같은 규칙이어야 한다."""

from __future__ import annotations

from typing import Any, Self, cast

from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.line_stops import resolve_leg_direction

# GTX-A: DN은 수서(10) → 성남(11) → 동탄(12), UP은 그 반대.
LINE_STOPS = [
    (10, "DN", 1),
    (11, "DN", 2),
    (12, "DN", 4),
    (12, "UP", 1),
    (11, "UP", 3),
    (10, "UP", 4),
]


class FakeCursor:
    def __init__(self, rows: list[tuple[int, str, int]]) -> None:
        self._all = rows
        self._rows: list[tuple[int, str, int]] = []

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def execute(self, sql: str, params: tuple[Any, ...]) -> None:
        stop_ids = params[1]
        self._rows = [row for row in self._all if row[0] in stop_ids]

    def fetchall(self) -> list[tuple[int, str, int]]:
        return self._rows


class FakeConnection:
    def __init__(self, rows: list[tuple[int, str, int]]) -> None:
        self._rows = rows

    def cursor(self) -> FakeCursor:
        return FakeCursor(self._rows)


def fake(rows: list[tuple[int, str, int]]) -> Connection:
    return cast(Connection, FakeConnection(rows))


def test_direction_follows_the_board_to_alight_order() -> None:
    conn = fake(LINE_STOPS)

    # 동탄(12) → 수서(10)는 UP, 반대는 DN.
    assert resolve_leg_direction(conn, 1, 12, 10) == "UP"
    assert resolve_leg_direction(conn, 1, 10, 12) == "DN"
    # 중간역도 마찬가지.
    assert resolve_leg_direction(conn, 1, 11, 10) == "UP"
    assert resolve_leg_direction(conn, 1, 11, 12) == "DN"


def test_direction_is_unknown_when_the_line_has_no_stop_order() -> None:
    assert resolve_leg_direction(fake([]), 1, 12, 10) is None


def test_terminal_without_an_alight_stop_falls_back_to_its_only_direction() -> None:
    conn = fake([(12, "UP", 1)])
    assert resolve_leg_direction(conn, 1, 12, None) == "UP"
