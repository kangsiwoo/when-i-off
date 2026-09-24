"""KST 벽시계 ↔ 절대 시각, 그리고 day_type 판정 (backend DayTypeResolver와 같은 규칙)."""

from __future__ import annotations

from datetime import date, time

from conftest import kst
from whenioff_analytics.daytype import (
    UTC,
    DayType,
    kst_date_of,
    kst_time_of,
    kst_wall_clock_to_instant,
    parse_holidays,
    resolve_day_type,
)


def test_weekday_saturday_sunday() -> None:
    assert resolve_day_type(date(2026, 5, 1)) is DayType.WEEKDAY
    assert resolve_day_type(date(2026, 5, 2)) is DayType.SATURDAY
    assert resolve_day_type(date(2026, 5, 3)) is DayType.SUNDAY_HOLIDAY


def test_listed_and_substitute_holidays_are_sunday_holiday() -> None:
    # 어린이날(화), 삼일절 대체공휴일(월). 근로자의날(5/1)은 법정공휴일이 아니라 평일이다.
    assert resolve_day_type(date(2026, 5, 5)) is DayType.SUNDAY_HOLIDAY
    assert resolve_day_type(date(2026, 3, 2)) is DayType.SUNDAY_HOLIDAY


def test_day_type_changes_across_midnight() -> None:
    # 금→토, 일→월, 공휴일 전날(한글날은 금요일) — 자정을 넘기면 시간표가 바뀐다.
    assert resolve_day_type(date(2026, 9, 18)) is DayType.WEEKDAY
    assert resolve_day_type(date(2026, 9, 19)) is DayType.SATURDAY
    assert resolve_day_type(date(2026, 9, 20)) is DayType.SUNDAY_HOLIDAY
    assert resolve_day_type(date(2026, 9, 21)) is DayType.WEEKDAY
    assert resolve_day_type(date(2026, 10, 8)) is DayType.WEEKDAY
    assert resolve_day_type(date(2026, 10, 9)) is DayType.SUNDAY_HOLIDAY


def test_wall_clock_gets_a_service_date_and_becomes_utc() -> None:
    instant = kst_wall_clock_to_instant(date(2026, 9, 22), time(8, 14))
    assert instant.tzinfo is UTC
    assert instant.isoformat() == "2026-09-21T23:14:00+00:00"


def test_wall_clock_after_midnight_belongs_to_its_own_service_date() -> None:
    # 막차 00:06은 전날 23:xx가 아니라 그 날짜의 00:06이다.
    assert kst_wall_clock_to_instant(date(2026, 9, 22), time(0, 6)).isoformat() == "2026-09-21T15:06:00+00:00"


def test_kst_projection_of_an_instant() -> None:
    moment = kst("2026-09-22 00:06")
    assert kst_date_of(moment) == date(2026, 9, 22)
    assert kst_time_of(moment) == time(0, 6)
    # 같은 순간의 UTC 날짜는 하루 앞이다 — day_type을 UTC 날짜로 판정하면 안 되는 이유.
    assert moment.astimezone(UTC).date() == date(2026, 9, 21)


def test_holiday_file_comments_and_blank_lines_are_ignored() -> None:
    parsed = parse_holidays("# 주석\n\n2026-01-01   # 신정\n   \n2026-12-25\n")
    assert parsed == frozenset({date(2026, 1, 1), date(2026, 12, 25)})
