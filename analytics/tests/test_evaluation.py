"""evaluate의 순수 계산 (#72, 합성 데이터)."""

from __future__ import annotations

from dataclasses import replace
from datetime import date, datetime, timedelta

from conftest import kst
from whenioff_analytics.model.evaluation import (
    AttemptRecord,
    Evaluation,
    RecommendationRecord,
    TripRecord,
    choose_trip,
    evaluate,
    evaluate_all,
    latest_recommendations,
    stop_wait_sec,
    summarize,
)

ROUTE = 1
DAY = date(2026, 9, 29)
TARGET = kst("2026-09-29 09:00")
TRANSIT_LEGS = {ROUTE: [2]}


def rec(
    rec_id: int,
    computed: str,
    leave: str = "2026-09-29 07:55",
    version: str = "v2",
    day: date = DAY,
    target: datetime = TARGET,
) -> RecommendationRecord:
    return RecommendationRecord(
        id=rec_id,
        commute_route_id=ROUTE,
        target_date=day,
        model_version=version,
        target_arrival_at=target,
        recommended_leave_home_at=kst(leave),
        computed_at=kst(computed),
    )


def attempt(
    seq: int,
    result: str,
    arrived: str | None = None,
    departed: str | None = None,
    leg: int = 2,
) -> AttemptRecord:
    return AttemptRecord(
        route_leg_id=leg,
        attempt_seq=seq,
        result=result,
        arrived_at_stop_at=None if arrived is None else kst(arrived),
        vehicle_actual_departure_at=None if departed is None else kst(departed),
    )


def trip(
    trip_id: int,
    left: str | None = "2026-09-29 08:00",
    arrived: str | None = "2026-09-29 08:58",
    attempts: tuple[AttemptRecord, ...] = (),
    day: date = DAY,
) -> TripRecord:
    return TripRecord(
        id=trip_id,
        commute_route_id=ROUTE,
        trip_date=day,
        left_home_at=None if left is None else kst(left),
        arrived_destination_at=None if arrived is None else kst(arrived),
        attempts=attempts,
    )


# ---------------------------------------------------------------------------
# 매칭
# ---------------------------------------------------------------------------


def test_latest_recommendation_per_date_and_version_wins() -> None:
    rows = [
        rec(1, "2026-09-29 03:00", version="v1"),
        rec(2, "2026-09-29 06:00", version="v1"),
        rec(3, "2026-09-29 05:00", version="v1"),
        rec(4, "2026-09-29 03:00", version="v2"),
        rec(5, "2026-09-29 03:00", version="v2"),  # 같은 computed_at이면 id가 큰 쪽
        rec(6, "2026-09-30 03:00", version="v1", day=date(2026, 9, 30)),
    ]
    assert [r.id for r in latest_recommendations(rows)] == [2, 5, 6]


def test_latest_recommendation_may_have_another_target() -> None:
    """목표 시각이 여러 개인 날도 버전당 마지막 계산 하나다 (#62)."""
    later_target = rec(2, "2026-09-29 07:00", target=kst("2026-09-29 09:10"))
    assert latest_recommendations([rec(1, "2026-09-29 06:00"), later_target]) == [later_target]


def test_evaluate_all_matches_same_route_and_date_and_skips_days_without_trip() -> None:
    recs = [rec(1, "2026-09-29 06:00"), rec(2, "2026-09-30 06:00", day=date(2026, 9, 30))]
    trips = [trip(10), trip(11, day=date(2026, 10, 1))]
    evaluations = evaluate_all(recs, trips, TRANSIT_LEGS)
    assert [(e.departure_recommendation_id, e.commute_trip_id) for e in evaluations] == [(1, 10)]


def test_evaluate_all_evaluates_each_version_against_the_day_trip() -> None:
    recs = [rec(1, "2026-09-29 06:00", version="v1"), rec(2, "2026-09-29 06:00", version="v2")]
    evaluations = evaluate_all(recs, [trip(10)], TRANSIT_LEGS)
    assert [(e.model_version, e.commute_trip_id) for e in evaluations] == [("v1", 10), ("v2", 10)]


def test_other_route_trip_is_not_matched() -> None:
    other = TripRecord(
        id=10, commute_route_id=2, trip_date=DAY, left_home_at=None, arrived_destination_at=None
    )
    assert evaluate_all([rec(1, "2026-09-29 06:00")], [other], TRANSIT_LEGS) == []


# ---------------------------------------------------------------------------
# 여러 trip
# ---------------------------------------------------------------------------


def test_choose_trip_closest_arrival_to_target() -> None:
    early = trip(1, arrived="2026-09-29 08:40")
    near_late = trip(2, arrived="2026-09-29 09:05")
    far = trip(3, arrived="2026-09-29 18:30")
    assert choose_trip([early, far, near_late], TARGET) == near_late


def test_choose_trip_tie_prefers_earlier_arrival_then_id() -> None:
    before = trip(5, arrived="2026-09-29 08:55")
    after = trip(4, arrived="2026-09-29 09:05")
    assert choose_trip([after, before], TARGET) == before
    same_a, same_b = trip(8, arrived="2026-09-29 08:55"), trip(7, arrived="2026-09-29 08:55")
    assert choose_trip([same_a, same_b], TARGET) == same_b


def test_choose_trip_prefers_arrived_trip_over_unfinished() -> None:
    unfinished = trip(1, left="2026-09-29 07:00", arrived=None)
    finished = trip(2, left="2026-09-29 12:00", arrived="2026-09-29 13:00")
    assert choose_trip([unfinished, finished], TARGET) == finished


def test_choose_trip_without_arrivals_takes_earliest_departure() -> None:
    no_left = trip(1, left=None, arrived=None)
    later = trip(2, left="2026-09-29 08:10", arrived=None)
    earlier = trip(3, left="2026-09-29 07:50", arrived=None)
    assert choose_trip([no_left, later, earlier], TARGET) == earlier
    assert choose_trip([trip(9, left=None, arrived=None), no_left], TARGET) == no_left
    assert choose_trip([], TARGET) is None


# ---------------------------------------------------------------------------
# 지각·차이
# ---------------------------------------------------------------------------


def test_exactly_on_target_is_not_late() -> None:
    e = evaluate(rec(1, "2026-09-29 06:00"), trip(1, arrived="2026-09-29 09:00"), [])
    assert e.is_late is False
    assert e.arrival_diff_sec == 0


def test_one_second_after_target_is_late() -> None:
    e = evaluate(rec(1, "2026-09-29 06:00"), trip(1, arrived="2026-09-29 09:00:01"), [])
    assert e.is_late is True
    assert e.arrival_diff_sec == 1


def test_sub_second_late_is_late_even_if_diff_rounds_to_zero() -> None:
    late = replace(trip(1), arrived_destination_at=TARGET + timedelta(milliseconds=300))
    e = evaluate(rec(1, "2026-09-29 06:00"), late, [])
    assert e.is_late is True
    assert e.arrival_diff_sec == 0


def test_departure_and_arrival_diff_signs() -> None:
    e = evaluate(
        rec(1, "2026-09-29 06:00", leave="2026-09-29 07:55"),
        trip(1, left="2026-09-29 08:00:30", arrived="2026-09-29 08:58"),
        [],
    )
    assert e.departure_diff_sec == 330  # 5분 30초 늦게 나갔다
    assert e.arrival_diff_sec == -120  # 2분 일찍 도착
    assert e.is_late is False
    assert e.actual_left_home_at == kst("2026-09-29 08:00:30")
    assert e.target_arrival_at == TARGET


def test_trip_without_arrival_has_no_lateness() -> None:
    e = evaluate(rec(1, "2026-09-29 06:00"), trip(1, arrived=None), [2])
    assert e.is_late is None
    assert e.arrival_diff_sec is None
    assert e.actual_arrived_at is None
    assert e.departure_diff_sec == 300


def test_trip_without_departure_has_no_departure_diff() -> None:
    e = evaluate(rec(1, "2026-09-29 06:00"), trip(1, left=None), [])
    assert e.departure_diff_sec is None
    assert e.is_late is False


# ---------------------------------------------------------------------------
# 탑승·놓침
# ---------------------------------------------------------------------------


def test_all_legs_caught_and_missed_count() -> None:
    attempts = (
        attempt(1, "MISSED", leg=2),
        attempt(2, "CAUGHT", leg=2),
        attempt(1, "MISSED", leg=4),
        attempt(2, "MISSED", leg=4),
        attempt(3, "UNKNOWN", leg=4),
    )
    e = evaluate(rec(1, "2026-09-29 06:00"), trip(1, attempts=attempts), [2, 4])
    assert e.all_legs_caught is False
    assert e.missed_count == 3
    assert evaluate(rec(1, "2026-09-29 06:00"), trip(1, attempts=attempts), [2]).all_legs_caught is True


def test_route_without_transit_is_all_caught() -> None:
    assert evaluate(rec(1, "2026-09-29 06:00"), trip(1), []).all_legs_caught is True


# ---------------------------------------------------------------------------
# 정류장 대기
# ---------------------------------------------------------------------------


def test_wait_includes_missed_vehicle_time() -> None:
    """놓친 뒤 다음 차를 탔으면 첫 시도의 정류장 도착부터 탄 차의 출발까지다."""
    attempts = (
        attempt(1, "MISSED", arrived="2026-09-29 07:31:10", departed="2026-09-29 07:32:20"),
        attempt(2, "CAUGHT", arrived=None, departed="2026-09-29 07:47:40"),
    )
    assert stop_wait_sec(attempts) == 990


def test_wait_averages_over_caught_legs_with_both_times() -> None:
    attempts = (
        attempt(1, "CAUGHT", arrived="2026-09-29 07:30:00", departed="2026-09-29 07:31:00", leg=2),
        attempt(1, "CAUGHT", arrived="2026-09-29 08:00:00", departed="2026-09-29 08:02:01", leg=4),
        # 시각이 빠진 구간은 평균에서 빠진다
        attempt(1, "CAUGHT", arrived=None, departed="2026-09-29 08:30:00", leg=6),
        attempt(1, "CAUGHT", arrived="2026-09-29 08:40:00", departed=None, leg=8),
    )
    assert stop_wait_sec(attempts) == 91  # (60 + 121) / 2 = 90.5 → 0에서 먼 쪽


def test_wait_ignores_legs_not_caught_and_missing_data() -> None:
    assert stop_wait_sec(()) is None
    assert stop_wait_sec((attempt(1, "MISSED", "2026-09-29 07:30", "2026-09-29 07:31"),)) is None
    assert stop_wait_sec((attempt(1, "UNKNOWN", "2026-09-29 07:30", "2026-09-29 07:31"),)) is None
    assert stop_wait_sec((attempt(1, "CAUGHT", None, "2026-09-29 07:31"),)) is None
    # 첫 시도가 없으면(attempt_seq 1이 빠진 데이터) 정류장 도착을 모른다
    assert stop_wait_sec((attempt(2, "CAUGHT", "2026-09-29 07:30", "2026-09-29 07:31"),)) is None


def test_negative_wait_is_dropped_and_zero_is_kept() -> None:
    negative = attempt(1, "CAUGHT", "2026-09-29 07:31", "2026-09-29 07:30", leg=2)
    zero = attempt(1, "CAUGHT", "2026-09-29 08:00", "2026-09-29 08:00", leg=4)
    assert stop_wait_sec((negative,)) is None
    assert stop_wait_sec((negative, zero)) == 0


# ---------------------------------------------------------------------------
# 요약
# ---------------------------------------------------------------------------


def _evaluation(version: str, late: bool | None, departure: int | None, wait: int | None) -> Evaluation:
    return Evaluation(
        commute_route_id=ROUTE,
        target_date=DAY,
        model_version=version,
        departure_recommendation_id=1,
        commute_trip_id=1,
        target_arrival_at=TARGET,
        recommended_leave_home_at=TARGET,
        actual_left_home_at=None,
        actual_arrived_at=None,
        departure_diff_sec=departure,
        arrival_diff_sec=None,
        is_late=late,
        all_legs_caught=True,
        missed_count=0,
        avg_stop_wait_sec=wait,
    )


def test_summarize_per_version() -> None:
    summaries = summarize(
        [
            _evaluation("v2", True, 60, 100),
            _evaluation("v1", False, -30, None),
            _evaluation("v2", False, 120, None),
            _evaluation("v2", None, None, 50),
        ]
    )
    assert [s.model_version for s in summaries] == ["v1", "v2"]
    v1, v2 = summaries
    assert (v1.n, v1.late, v1.with_arrival, v1.late_rate) == (1, 0, 1, 0.0)
    assert v1.mean_stop_wait_sec is None
    assert (v2.n, v2.late, v2.with_arrival) == (3, 1, 2)
    assert v2.late_rate == 0.5
    assert v2.mean_departure_diff_sec == 90.0
    assert v2.mean_stop_wait_sec == 75.0


def test_summarize_without_arrivals_has_no_late_rate() -> None:
    (summary,) = summarize([_evaluation("v2", None, None, None)])
    assert summary.late_rate is None
    assert summary.mean_departure_diff_sec is None
