"""일괄 추천(`recommend --all-active-routes`, #68)의 "오늘 어느 경로를 어느 목표 시각으로 돌릴지" 판정.

순수 함수다 — DB도 시계도 모른다. 지금 시각(`now`)과 경로의 기본 목표(KST 벽시계 + 대상 day_type 집합)를
받아 경로마다 계산할 목표 도착 시각 또는 건너뛰는 이유를 낸다.

- **운행일**: 목표 도착 시각이 속한 KST 날짜. day_type은 이 날짜로 정한다 (공휴일은 SUNDAY_HOLIDAY)
- **창**: [목표 − 120분, 목표 + 30분] (양 끝 포함). `--only-in-window`면 `now`가 이 안일 때만 계산한다
- **`--date` 생략**: `now`의 KST 날짜. 단 창 판정을 할 때는 전날·다음 날의 목표도 본다 — 목표 00:30의 창은
  전날 22:30에 열리고, 목표 23:50의 창은 다음 날 00:20에 닫힌다. 창 길이(150분)가 하루보다 짧아
  한 경로에 걸리는 운행일은 많아야 하나다
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from enum import StrEnum

from whenioff_analytics.daytype import KST, DayType, kst_date_of, kst_wall_clock_to_instant, resolve_day_type

WINDOW_BEFORE = timedelta(minutes=120)
WINDOW_AFTER = timedelta(minutes=30)


@dataclass(frozen=True)
class RouteTarget:
    """활성 경로 하나의 기본 목표. `arrival_time`은 KST 벽시계, 없으면 일괄 추천에서 빠진다."""

    route_id: int
    name: str
    arrival_time: time | None
    day_types: frozenset[DayType]


class SkipReason(StrEnum):
    NO_TARGET_TIME = "no_target_time"
    DAY_TYPE_EXCLUDED = "day_type_excluded"
    OUTSIDE_WINDOW = "outside_window"


@dataclass(frozen=True)
class Planned:
    route: RouteTarget
    service_date: date
    day_type: DayType
    target_arrival_at: datetime


@dataclass(frozen=True)
class Skipped:
    route: RouteTarget
    reason: SkipReason
    detail: str


def window_of(target_arrival_at: datetime) -> tuple[datetime, datetime]:
    return target_arrival_at - WINDOW_BEFORE, target_arrival_at + WINDOW_AFTER


def in_window(now: datetime, target_arrival_at: datetime) -> bool:
    start, end = window_of(target_arrival_at)
    return start <= now <= end


def default_service_date(now: datetime) -> date:
    """`--date`를 생략했을 때의 날짜: 지금의 KST 날짜 (호스트가 UTC여도 07:00 KST는 이미 그날이다)."""
    return kst_date_of(now)


def plan_targets(
    routes: Iterable[RouteTarget],
    now: datetime,
    service_date: date | None = None,
    only_in_window: bool = False,
    holidays: frozenset[date] | None = None,
) -> list[Planned | Skipped]:
    """경로마다 계산할 목표(`Planned`) 또는 건너뛸 이유(`Skipped`). 입력 순서를 지킨다.

    `service_date`가 있으면 그날만 본다. 없으면 `now`의 KST 날짜이고, `only_in_window`면 그 전날·다음 날도
    후보로 본다 (모듈 문서의 자정 경계).
    """
    if service_date is not None:
        dates: Sequence[date] = (service_date,)
    elif only_in_window:
        today = default_service_date(now)
        dates = (today - timedelta(days=1), today, today + timedelta(days=1))
    else:
        dates = (default_service_date(now),)
    return [_plan_one(route, now, dates, only_in_window, holidays) for route in routes]


def _plan_one(
    route: RouteTarget,
    now: datetime,
    dates: Sequence[date],
    only_in_window: bool,
    holidays: frozenset[date] | None,
) -> Planned | Skipped:
    if route.arrival_time is None:
        return Skipped(route, SkipReason.NO_TARGET_TIME, "no default target arrival time")
    candidates = [
        Planned(
            route=route,
            service_date=day,
            day_type=resolve_day_type(day, holidays),
            target_arrival_at=kst_wall_clock_to_instant(day, route.arrival_time),
        )
        for day in dates
    ]
    if only_in_window:
        inside = [c for c in candidates if in_window(now, c.target_arrival_at)]
        if not inside:
            # 창 밖이면 day_type은 따질 필요도 없다. 이유는 가장 가까운(가운데) 날짜로 적는다.
            middle = candidates[len(candidates) // 2]
            start, end = window_of(middle.target_arrival_at)
            return Skipped(
                route,
                SkipReason.OUTSIDE_WINDOW,
                f"now is outside {_kst(start)}..{_kst(end)}",
            )
        candidates = inside
    chosen = candidates[0]
    if chosen.day_type not in route.day_types:
        allowed = ",".join(day for day in DayType if day in route.day_types)
        return Skipped(
            route,
            SkipReason.DAY_TYPE_EXCLUDED,
            f"{chosen.service_date} is {chosen.day_type}, route runs on {allowed}",
        )
    return chosen


def _kst(moment: datetime) -> str:
    return moment.astimezone(KST).strftime("%Y-%m-%d %H:%M")
