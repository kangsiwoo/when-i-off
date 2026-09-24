"""시간표(KST 벽시계) → 후보 차량(절대 시각) 전개. DB 대신 가짜 커서를 쓴다."""

from __future__ import annotations

from datetime import time
from typing import Any, Self, cast

import pytest

from conftest import kst
from whenioff_analytics.daytype import DayType
from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.schedules import load_scheduled_departures
from whenioff_analytics.io.signals import CycleBand, select_cycle
from whenioff_analytics.model.distributions import SignalCycle


class FakeCursor:
    def __init__(self, timetable: dict[DayType, tuple[time, ...]], asked: list[str]) -> None:
        self._timetable = timetable
        self._asked = asked
        self._rows: list[tuple[time]] = []

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def execute(self, sql: str, params: tuple[Any, ...]) -> None:
        day_type = DayType(params[2])
        self._asked.append(day_type.value)
        self._rows = [(t,) for t in self._timetable.get(day_type, ())]

    def fetchall(self) -> list[tuple[time]]:
        return self._rows


class FakeConnection:
    def __init__(self, timetable: dict[DayType, tuple[time, ...]]) -> None:
        self._timetable = timetable
        self.asked: list[str] = []

    def cursor(self) -> FakeCursor:
        return FakeCursor(self._timetable, self.asked)


def fake(timetable: dict[DayType, tuple[time, ...]]) -> tuple[Connection, FakeConnection]:
    conn = FakeConnection(timetable)
    return cast(Connection, conn), conn


def test_schedule_times_get_a_service_date_and_are_filtered_to_the_window() -> None:
    conn, _ = fake({DayType.WEEKDAY: (time(7, 55), time(8, 14), time(8, 28), time(9, 0))})

    found = load_scheduled_departures(conn, 1, 2, kst("2026-09-22 08:00"), kst("2026-09-22 08:30"))

    assert [d.scheduled_time for d in found] == [time(8, 14), time(8, 28)]
    assert found[0].departure_at.isoformat() == "2026-09-21T23:14:00+00:00"
    assert found[0].service_date.isoformat() == "2026-09-22"


def test_window_crossing_midnight_re_resolves_the_day_type() -> None:
    conn, spy = fake(
        {
            DayType.WEEKDAY: (time(23, 30),),
            DayType.SATURDAY: (time(0, 20),),
        }
    )

    # 금요일 23:00 ~ 토요일 01:00
    found = load_scheduled_departures(conn, 1, 2, kst("2026-09-18 23:00"), kst("2026-09-19 01:00"))

    assert [(d.service_date.isoformat(), d.scheduled_time, d.day_type) for d in found] == [
        ("2026-09-18", time(23, 30), DayType.WEEKDAY),
        ("2026-09-19", time(0, 20), DayType.SATURDAY),
    ]
    assert spy.asked == ["WEEKDAY", "SATURDAY"]


def test_window_crossing_into_a_holiday_uses_the_holiday_timetable() -> None:
    conn, spy = fake({DayType.WEEKDAY: (time(23, 40),), DayType.SUNDAY_HOLIDAY: (time(0, 30),)})

    # 목요일 → 한글날(금)
    found = load_scheduled_departures(conn, 1, 2, kst("2026-10-08 23:00"), kst("2026-10-09 01:00"))

    assert [d.day_type for d in found] == [DayType.WEEKDAY, DayType.SUNDAY_HOLIDAY]
    assert spy.asked == ["WEEKDAY", "SUNDAY_HOLIDAY"]


def test_each_day_type_is_queried_once() -> None:
    conn, spy = fake({DayType.WEEKDAY: (time(8, 0),)})
    # 월~수 (모두 평일) 3일치 창인데 시간표 조회는 한 번이어야 한다
    load_scheduled_departures(conn, 1, 2, kst("2026-09-21 00:00"), kst("2026-09-23 23:59"))
    assert spy.asked == ["WEEKDAY"]


def test_reversed_window_is_rejected() -> None:
    conn, _ = fake({})
    with pytest.raises(ValueError, match="window_end"):
        load_scheduled_departures(conn, 1, 2, kst("2026-09-22 09:00"), kst("2026-09-22 08:00"))


def test_signal_cycle_band_selection() -> None:
    bands = (
        CycleBand(time(7, 0), time(9, 0), SignalCycle(cycle_sec=140.0, red_sec=100.0)),
        CycleBand(time(9, 0), time(18, 0), SignalCycle(cycle_sec=120.0, red_sec=80.0)),
    )
    assert select_cycle(bands, time(8, 30)) == bands[0].cycle
    assert select_cycle(bands, time(9, 0)) == bands[1].cycle
    assert select_cycle(bands, time(6, 59)) is None
    assert select_cycle((), time(8, 30)) is None
