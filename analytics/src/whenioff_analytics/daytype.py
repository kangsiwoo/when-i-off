"""KST 달력: 날짜 → `day_type`, 그리고 시간표의 벽시계 시각 → 절대 시각.

backend의 `DayTypeResolver`/`TransitScheduleService`와 같은 규칙을 Python으로 다시 구현한 것이다
(모듈이 따로 패키징되므로 backend 리소스를 런타임에 읽지 않는다). 공휴일 목록은
`resources/calendar/kr-holidays.txt`에 backend와 같은 내용을 복사해 두고, 두 파일이 어긋나면
`tests/test_holiday_list.py`가 CI에서 깨진다.
"""

from __future__ import annotations

from datetime import date, datetime, time
from enum import StrEnum
from functools import lru_cache
from importlib.resources import files
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")
UTC = ZoneInfo("UTC")

HOLIDAY_RESOURCE = "calendar/kr-holidays.txt"


class DayType(StrEnum):
    """PostgreSQL `day_type` enum과 같은 값."""

    WEEKDAY = "WEEKDAY"
    SATURDAY = "SATURDAY"
    SUNDAY_HOLIDAY = "SUNDAY_HOLIDAY"


def parse_holidays(content: str) -> frozenset[date]:
    dates = []
    for raw in content.splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            dates.append(date.fromisoformat(line))
    return frozenset(dates)


@lru_cache(maxsize=1)
def korean_holidays() -> frozenset[date]:
    resource = files("whenioff_analytics.resources").joinpath(HOLIDAY_RESOURCE)
    return parse_holidays(resource.read_text(encoding="utf-8"))


def resolve_day_type(service_date: date, holidays: frozenset[date] | None = None) -> DayType:
    """목록에 없는 해의 공휴일은 backend와 마찬가지로 평일/토요일로 떨어진다."""
    listed = korean_holidays() if holidays is None else holidays
    if service_date.weekday() == 6 or service_date in listed:
        return DayType.SUNDAY_HOLIDAY
    if service_date.weekday() == 5:
        return DayType.SATURDAY
    return DayType.WEEKDAY


def kst_wall_clock_to_instant(service_date: date, wall_clock: time) -> datetime:
    """시간표의 KST 벽시계 시각에 운행일을 붙여 절대 시각(UTC)으로 바꾼다.

    KST는 서머타임이 없어 (운행일 + 시각)이 항상 한 순간으로만 해석된다.
    """
    return datetime.combine(service_date, wall_clock, tzinfo=KST).astimezone(UTC)


def kst_date_of(moment: datetime) -> date:
    return moment.astimezone(KST).date()


def kst_time_of(moment: datetime) -> time:
    return moment.astimezone(KST).timetz().replace(tzinfo=None)
