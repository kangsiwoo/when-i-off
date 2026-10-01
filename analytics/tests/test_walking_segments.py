"""WALK 구간 실측 파생 (DATA_MODEL `walking_segments`, 합성 데이터)."""

from __future__ import annotations

from datetime import timedelta
from itertools import pairwise

import pytest

from conftest import kst
from whenioff_analytics.model.distributions import haversine_m
from whenioff_analytics.model.walking import (
    Attempt,
    DistanceSource,
    SkipReason,
    TracePoint,
    TripEvents,
    TripLeg,
    derive_walking_segments,
    gps_distance_m,
)

HOME = (37.2000, 127.0950)
STATION_A = (37.2010, 127.0960)
STATION_B = (37.4870, 127.1010)
OFFICE = (37.4880, 127.1020)


def walk(leg_id: int, start: tuple[float, float], end: tuple[float, float], planned: float | None) -> TripLeg:
    return TripLeg(route_leg_id=leg_id, leg_type="WALK", planned_distance_m=planned, start=start, end=end)


def transit(leg_id: int, *attempts: Attempt) -> TripLeg:
    return TripLeg(
        route_leg_id=leg_id,
        leg_type="TRANSIT",
        planned_distance_m=None,
        start=None,
        end=None,
        attempts=attempts,
    )


def caught(seq: int, arrived: str | None, alighted: str | None) -> Attempt:
    return Attempt(
        attempt_seq=seq,
        result="CAUGHT",
        arrived_at_stop_at=None if arrived is None else kst(arrived),
        alighted_at=None if alighted is None else kst(alighted),
    )


def missed(seq: int, arrived: str) -> Attempt:
    return Attempt(attempt_seq=seq, result="MISSED", arrived_at_stop_at=kst(arrived), alighted_at=None)


def trip(
    *legs: TripLeg,
    left: str | None = "2026-09-22 08:00",
    arrived: str | None = "2026-09-22 09:00",
    traces: tuple[TracePoint, ...] = (),
) -> TripEvents:
    return TripEvents(
        commute_trip_id=7,
        left_home_at=None if left is None else kst(left),
        arrived_destination_at=None if arrived is None else kst(arrived),
        legs=legs,
        traces=traces,
    )


def track(
    start: str, *points: tuple[float, float], every_sec: int = 30, accuracy: float = 10.0
) -> tuple[TracePoint, ...]:
    t0 = kst(start)
    return tuple(
        TracePoint(recorded_at=t0 + timedelta(seconds=i * every_sec), lat=lat, lng=lng, accuracy_m=accuracy)
        for i, (lat, lng) in enumerate(points)
    )


def test_first_transfer_and_last_walks_take_their_ends_from_the_adjacent_events() -> None:
    """첫 WALK는 집 → 역 도착, 환승 WALK는 하차 → 다음 역 도착, 마지막 WALK는 하차 → 목적지."""
    events = trip(
        walk(1, HOME, STATION_A, 400.0),
        transit(2, caught(1, "2026-09-22 08:06", "2026-09-22 08:30")),
        walk(3, STATION_A, STATION_B, 150.0),
        transit(4, caught(1, "2026-09-22 08:33", "2026-09-22 08:50")),
        walk(5, STATION_B, OFFICE, 600.0),
    )

    segments, skipped = derive_walking_segments(events)

    assert skipped == []
    assert [(s.route_leg_id, s.started_at, s.ended_at) for s in segments] == [
        (1, kst("2026-09-22 08:00"), kst("2026-09-22 08:06")),
        (3, kst("2026-09-22 08:30"), kst("2026-09-22 08:33")),
        (5, kst("2026-09-22 08:50"), kst("2026-09-22 09:00")),
    ]
    first = segments[0]
    assert first.duration_sec == 360
    assert first.distance_m == 400.0
    assert first.distance_source is DistanceSource.PLANNED
    assert first.avg_speed_mps == pytest.approx(400.0 / 360)


def test_missed_then_caught_uses_the_first_arrival_and_the_caught_alighting() -> None:
    """정류장에서 다음 차를 기다린 시간은 도보가 아니다. 놓친 차의 도착이 끝, 탄 차의 하차가 시작이다."""
    events = trip(
        walk(1, HOME, STATION_A, 400.0),
        # 순서가 섞여 들어와도 attempt_seq로 고른다.
        transit(2, caught(2, "2026-09-22 08:15", "2026-09-22 08:40"), missed(1, "2026-09-22 08:05")),
        walk(3, STATION_A, OFFICE, 300.0),
    )

    segments, skipped = derive_walking_segments(events)

    assert skipped == []
    assert [(s.started_at, s.ended_at) for s in segments] == [
        (kst("2026-09-22 08:00"), kst("2026-09-22 08:05")),
        (kst("2026-09-22 08:40"), kst("2026-09-22 09:00")),
    ]


def test_walk_only_route_spans_home_to_destination() -> None:
    segments, skipped = derive_walking_segments(
        trip(walk(1, HOME, OFFICE, 1200.0), arrived="2026-09-22 08:20")
    )

    assert skipped == []
    assert len(segments) == 1
    assert (segments[0].started_at, segments[0].ended_at) == (
        kst("2026-09-22 08:00"),
        kst("2026-09-22 08:20"),
    )
    assert segments[0].avg_speed_mps == pytest.approx(1.0)


def test_a_segment_with_a_missing_end_is_skipped_but_the_others_are_kept() -> None:
    """기록이 덜 된 trip: 하차를 못 잡았고(MISSED뿐) 목적지 도착도 없다."""
    events = trip(
        walk(1, HOME, STATION_A, 400.0),
        transit(2, missed(1, "2026-09-22 08:05")),
        walk(3, STATION_A, OFFICE, 300.0),
        arrived=None,
    )

    segments, skipped = derive_walking_segments(events)

    assert [s.route_leg_id for s in segments] == [1]
    assert [(s.route_leg_id, s.reason) for s in skipped] == [(3, SkipReason.MISSING_START)]


def test_missing_first_attempt_and_missing_left_home_are_skipped() -> None:
    no_first_attempt = trip(
        walk(1, HOME, STATION_A, 400.0),
        transit(2, caught(2, "2026-09-22 08:10", "2026-09-22 08:40")),
        walk(3, STATION_A, OFFICE, 300.0),
        left=None,
    )

    segments, skipped = derive_walking_segments(no_first_attempt)

    assert [s.route_leg_id for s in segments] == [3]
    assert [(s.route_leg_id, s.reason) for s in skipped] == [(1, SkipReason.MISSING_START)]


def test_end_not_after_start_is_skipped() -> None:
    """시계가 어긋나 역 도착이 집 나선 시각보다 이르거나, 반올림해 1초도 안 되면 속도를 못 낸다."""
    events = trip(
        walk(1, HOME, STATION_A, 400.0),
        transit(2, caught(1, "2026-09-22 07:59", "2026-09-22 08:30")),
        walk(3, STATION_A, OFFICE, 300.0),
        arrived="2026-09-22 08:30:00.4",
    )

    segments, skipped = derive_walking_segments(events)

    assert segments == []
    assert [s.reason for s in skipped] == [SkipReason.NON_POSITIVE_DURATION] * 2


def test_gps_distance_is_used_when_there_are_enough_points_inside_the_window() -> None:
    # 08:00부터 30초 간격 네 점 + 창 밖(08:10, 다음 구간) 한 점.
    path = (HOME, (37.2003, 127.0953), (37.2007, 127.0957), STATION_A)
    traces = (*track("2026-09-22 08:00", *path), *track("2026-09-22 08:10", OFFICE))
    events = trip(
        walk(1, HOME, STATION_A, 9999.0),
        transit(2, caught(1, "2026-09-22 08:06", "2026-09-22 08:50")),
        walk(3, STATION_B, OFFICE, 600.0),
        traces=traces,
    )

    segments, _ = derive_walking_segments(events)

    expected = sum(haversine_m(*a, *b) for a, b in pairwise(path))
    assert segments[0].distance_source is DistanceSource.GPS
    assert segments[0].distance_m == pytest.approx(expected)
    # 마지막 WALK 창에는 포인트가 하나뿐이라 계획 거리로 내려간다.
    assert segments[1].distance_source is DistanceSource.PLANNED
    assert segments[1].distance_m == 600.0


def test_without_gps_or_planned_distance_the_endpoints_great_circle_is_used() -> None:
    segments, _ = derive_walking_segments(trip(walk(1, HOME, OFFICE, None)))

    assert segments[0].distance_source is DistanceSource.GREAT_CIRCLE
    assert segments[0].distance_m == pytest.approx(haversine_m(*HOME, *OFFICE))


def test_inaccurate_points_are_dropped_before_accumulating() -> None:
    start, end = kst("2026-09-22 08:00"), kst("2026-09-22 08:10")
    good = track("2026-09-22 08:00", HOME, STATION_A, every_sec=120)
    # 정확도 300m짜리 튄 점: 남기면 수십 km가 더해진다.
    spike = track("2026-09-22 08:01", OFFICE, accuracy=300.0)
    unknown = TracePoint(
        recorded_at=kst("2026-09-22 08:03"), lat=STATION_A[0], lng=STATION_A[1], accuracy_m=None
    )

    assert gps_distance_m((*good, *spike), start, end) == pytest.approx(haversine_m(*HOME, *STATION_A))
    # 정확도가 비어 있는 점은 남긴다 (STATION_A에 한 번 더 있으니 거리는 그대로).
    assert gps_distance_m((*good, *spike, unknown), start, end) == pytest.approx(
        haversine_m(*HOME, *STATION_A)
    )
    # 기준을 넘는 점만 남으면 포인트 부족이다.
    assert gps_distance_m(spike + track("2026-09-22 08:02", HOME, accuracy=150.0), start, end) is None


def test_outliers_are_still_derived() -> None:
    """3 m/s를 넘는 속도도 저장한다. 제외는 calibrate의 몫이다."""
    segments, skipped = derive_walking_segments(
        trip(walk(1, HOME, OFFICE, 3600.0), arrived="2026-09-22 08:10")
    )

    assert skipped == []
    assert segments[0].avg_speed_mps == pytest.approx(6.0)


def test_haversine_is_symmetric_and_about_111_km_per_degree_of_latitude() -> None:
    assert haversine_m(37.0, 127.0, 38.0, 127.0) == pytest.approx(111_195, rel=1e-3)
    assert haversine_m(*HOME, *OFFICE) == pytest.approx(haversine_m(*OFFICE, *HOME))
    assert haversine_m(*HOME, *HOME) == 0.0
