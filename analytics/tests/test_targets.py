"""일괄 추천의 경로·목표 선택 (#68): 창 판정, day_type·공휴일, KST 자정 경계."""

from __future__ import annotations

from datetime import date, time

from conftest import kst
from whenioff_analytics.daytype import UTC, DayType
from whenioff_analytics.model.targets import (
    Planned,
    RouteTarget,
    Skipped,
    SkipReason,
    default_service_date,
    in_window,
    plan_targets,
)

WEEKDAYS = frozenset({DayType.WEEKDAY})


def route(
    arrival: time | None = time(9, 0), day_types: frozenset[DayType] = WEEKDAYS, route_id: int = 1
) -> RouteTarget:
    return RouteTarget(route_id=route_id, name=f"r{route_id}", arrival_time=arrival, day_types=day_types)


def only(plans: list[Planned | Skipped]) -> Planned | Skipped:
    assert len(plans) == 1
    return plans[0]


def test_window_is_120_minutes_before_to_30_minutes_after_inclusive() -> None:
    target = kst("2026-10-06 09:00")
    assert in_window(kst("2026-10-06 07:00"), target)
    assert in_window(kst("2026-10-06 09:30"), target)
    assert not in_window(kst("2026-10-06 06:59:59"), target)
    assert not in_window(kst("2026-10-06 09:30:01"), target)


def test_weekday_route_in_window_is_planned_with_kst_target() -> None:
    plan = only(plan_targets([route()], kst("2026-10-06 07:40"), only_in_window=True))
    assert isinstance(plan, Planned)
    assert plan.service_date == date(2026, 10, 6)
    assert plan.day_type is DayType.WEEKDAY
    assert plan.target_arrival_at == kst("2026-10-06 09:00")


def test_outside_window_is_skipped_only_with_the_flag() -> None:
    now = kst("2026-10-06 12:00")
    plan = only(plan_targets([route()], now, only_in_window=True))
    assert isinstance(plan, Skipped)
    assert plan.reason is SkipReason.OUTSIDE_WINDOW
    assert "2026-10-06 07:00..2026-10-06 09:30" in plan.detail
    # 플래그가 없으면 창과 무관하게 그날 목표로 계산한다 (backfill·수동 실행).
    assert isinstance(only(plan_targets([route()], now)), Planned)


def test_route_without_target_time_is_skipped() -> None:
    plan = only(plan_targets([route(arrival=None)], kst("2026-10-06 08:00")))
    assert isinstance(plan, Skipped)
    assert plan.reason is SkipReason.NO_TARGET_TIME


def test_holiday_is_sunday_holiday_and_excluded_from_weekday_routes() -> None:
    # 2026-10-05(월)는 개천절 대체공휴일 — 공유 공휴일 목록으로 SUNDAY_HOLIDAY다.
    plan = only(plan_targets([route()], kst("2026-10-05 08:00"), only_in_window=True))
    assert isinstance(plan, Skipped)
    assert plan.reason is SkipReason.DAY_TYPE_EXCLUDED
    assert "SUNDAY_HOLIDAY" in plan.detail

    every_day = frozenset(DayType)
    planned = only(plan_targets([route(day_types=every_day)], kst("2026-10-05 08:00"), only_in_window=True))
    assert isinstance(planned, Planned)
    assert planned.day_type is DayType.SUNDAY_HOLIDAY


def test_injected_holiday_list_is_used() -> None:
    tuesday = date(2026, 10, 6)
    plan = only(plan_targets([route()], kst("2026-10-06 08:00"), holidays=frozenset({tuesday})))
    assert isinstance(plan, Skipped)
    assert plan.reason is SkipReason.DAY_TYPE_EXCLUDED


def test_saturday_route() -> None:
    saturday_only = frozenset({DayType.SATURDAY})
    plans = plan_targets(
        [route(day_types=saturday_only, route_id=1), route(route_id=2)],
        kst("2026-10-10 08:00"),
        only_in_window=True,
    )
    assert isinstance(plans[0], Planned)
    assert plans[0].day_type is DayType.SATURDAY
    assert isinstance(plans[1], Skipped)
    assert plans[1].reason is SkipReason.DAY_TYPE_EXCLUDED


def test_explicit_date_overrides_now() -> None:
    plan = only(plan_targets([route()], kst("2026-10-01 23:00"), service_date=date(2026, 10, 6)))
    assert isinstance(plan, Planned)
    assert plan.target_arrival_at == kst("2026-10-06 09:00")
    # 날짜를 주고 창도 보면, 지금이 그날의 창 밖이라 건너뛴다.
    skipped = only(
        plan_targets([route()], kst("2026-10-01 23:00"), service_date=date(2026, 10, 6), only_in_window=True)
    )
    assert isinstance(skipped, Skipped)
    assert skipped.reason is SkipReason.OUTSIDE_WINDOW


def test_default_date_is_kst_even_when_the_clock_reads_utc() -> None:
    # UTC 10/5 22:30 = KST 10/6 07:30. 호스트 시계가 UTC여도 운행일은 KST 날짜다.
    now = kst("2026-10-06 07:30").astimezone(UTC)
    assert now.date() == date(2026, 10, 5)
    assert default_service_date(now) == date(2026, 10, 6)
    plan = only(plan_targets([route()], now, only_in_window=True))
    assert isinstance(plan, Planned)
    assert plan.service_date == date(2026, 10, 6)
    assert plan.day_type is DayType.WEEKDAY


def test_window_opening_before_kst_midnight_targets_the_next_day() -> None:
    # 목표 00:30의 창은 전날 22:30에 열린다. 화 23:00 KST에 돌면 수요일 00:30 목표다.
    plan = only(plan_targets([route(arrival=time(0, 30))], kst("2026-10-06 23:00"), only_in_window=True))
    assert isinstance(plan, Planned)
    assert plan.service_date == date(2026, 10, 7)
    assert plan.target_arrival_at == kst("2026-10-07 00:30")


def test_window_closing_after_kst_midnight_targets_the_previous_day() -> None:
    # 목표 23:50의 창은 다음 날 00:20에 닫힌다. 금 00:10 KST는 목요일 23:50 목표의 창 안이다.
    plan = only(plan_targets([route(arrival=time(23, 50))], kst("2026-10-09 00:10"), only_in_window=True))
    assert isinstance(plan, Planned)
    assert plan.service_date == date(2026, 10, 8)
    # 그 다음 날(10/9 한글날)의 day_type이 아니라 목표가 속한 날(목, 평일)로 판정한다.
    assert plan.day_type is DayType.WEEKDAY


def test_day_type_follows_the_target_date_across_midnight() -> None:
    # 일 23:00 KST에 돌면 목표는 월 00:30 — 월요일(평일)로 판정해 평일 경로가 돈다.
    plan = only(plan_targets([route(arrival=time(0, 30))], kst("2026-10-11 23:00"), only_in_window=True))
    assert isinstance(plan, Planned)
    assert plan.service_date == date(2026, 10, 12)
    assert plan.day_type is DayType.WEEKDAY


def test_input_order_is_kept() -> None:
    plans = plan_targets(
        [route(route_id=3), route(arrival=None, route_id=1), route(route_id=2)], kst("2026-10-06 08:00")
    )
    assert [p.route.route_id for p in plans] == [3, 1, 2]
