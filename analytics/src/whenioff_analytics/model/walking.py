"""trip 하나의 사건 시각과 GPS로 WALK 구간 실측(`walking_segments`)을 파생한다 (DATA_MODEL, ALGORITHM 5절).

경로는 WALK로 시작하고 끝나며 WALK/TRANSIT이 번갈아 온다 (backend `RouteLegService`가 강제한다).
그래서 WALK 구간의 시작은 "바로 앞 구간이 끝난 사건", 끝은 "바로 뒤 구간이 시작된 사건"으로
일반화된다 — 앞이 없으면 `left_home_at`, 뒤가 없으면 `arrived_destination_at`. 도보만 있는 경로는
WALK 하나가 집 → 목적지 전체다.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from itertools import pairwise

from whenioff_analytics.model.distributions import haversine_m, planned_walk_distance_m

GPS_MAX_ACCURACY_M = 100.0
"""이보다 부정확한 포인트는 거리 누적에서 뺀다. iOS가 업로드 전에 거르는 기준(#4)과 같다."""


@dataclass(frozen=True)
class Attempt:
    attempt_seq: int
    result: str
    arrived_at_stop_at: datetime | None
    alighted_at: datetime | None


@dataclass(frozen=True)
class TripLeg:
    route_leg_id: int
    leg_type: str
    planned_distance_m: float | None
    start: tuple[float, float] | None
    end: tuple[float, float] | None
    attempts: tuple[Attempt, ...] = ()


@dataclass(frozen=True)
class TracePoint:
    recorded_at: datetime
    lat: float
    lng: float
    accuracy_m: float | None


@dataclass(frozen=True)
class TripEvents:
    """trip 하나. `legs`는 `seq_order` 순서다."""

    commute_trip_id: int
    left_home_at: datetime | None
    arrived_destination_at: datetime | None
    legs: tuple[TripLeg, ...]
    traces: tuple[TracePoint, ...] = ()


class DistanceSource(StrEnum):
    GPS = "gps"
    PLANNED = "planned"
    GREAT_CIRCLE = "great-circle"


class SkipReason(StrEnum):
    MISSING_START = "missing_start"
    MISSING_END = "missing_end"
    NON_POSITIVE_DURATION = "non_positive_duration"
    NO_DISTANCE = "no_distance"


@dataclass(frozen=True)
class WalkingSegment:
    commute_trip_id: int
    route_leg_id: int
    started_at: datetime
    ended_at: datetime
    duration_sec: int
    distance_m: float
    avg_speed_mps: float
    distance_source: DistanceSource


@dataclass(frozen=True)
class SkippedSegment:
    commute_trip_id: int
    route_leg_id: int
    reason: SkipReason


def derive_walking_segments(trip: TripEvents) -> tuple[list[WalkingSegment], list[SkippedSegment]]:
    """trip의 WALK 구간마다 실측 한 건, 또는 건너뛴 이유 한 건을 낸다.

    이상치(너무 느리거나 빠른 속도)도 그대로 낸다. 제외는 `calibrate`의 몫이다.
    """
    segments: list[WalkingSegment] = []
    skipped: list[SkippedSegment] = []
    for index, leg in enumerate(trip.legs):
        if leg.leg_type != "WALK":
            continue
        before = trip.legs[index - 1] if index > 0 else None
        after = trip.legs[index + 1] if index + 1 < len(trip.legs) else None
        started_at = trip.left_home_at if before is None else _alighted_at(before)
        ended_at = trip.arrived_destination_at if after is None else _arrived_at_stop_at(after)

        if started_at is None or ended_at is None:
            # 기록이 덜 된 trip. 앞뒤가 WALK인 경우(경로 규칙상 없다)도 사건이 없으므로 여기로 온다.
            reason = SkipReason.MISSING_START if started_at is None else SkipReason.MISSING_END
            skipped.append(SkippedSegment(trip.commute_trip_id, leg.route_leg_id, reason))
            continue

        # 초 단위로 저장하므로 반올림해서 1초도 안 되면 "끝 ≤ 시작"과 같이 본다 (속도를 나눌 수 없다).
        duration_sec = round((ended_at - started_at).total_seconds())
        if duration_sec < 1:
            skipped.append(
                SkippedSegment(trip.commute_trip_id, leg.route_leg_id, SkipReason.NON_POSITIVE_DURATION)
            )
            continue

        measured = walk_distance(leg, trip.traces, started_at, ended_at)
        if measured is None:
            skipped.append(SkippedSegment(trip.commute_trip_id, leg.route_leg_id, SkipReason.NO_DISTANCE))
            continue
        distance_m, source = measured
        segments.append(
            WalkingSegment(
                commute_trip_id=trip.commute_trip_id,
                route_leg_id=leg.route_leg_id,
                started_at=started_at,
                ended_at=ended_at,
                duration_sec=duration_sec,
                distance_m=distance_m,
                avg_speed_mps=distance_m / duration_sec,
                distance_source=source,
            )
        )
    return segments, skipped


def walk_distance(
    leg: TripLeg, traces: tuple[TracePoint, ...], started_at: datetime, ended_at: datetime
) -> tuple[float, DistanceSource] | None:
    """GPS 누적 거리. 쓸 만한 포인트가 둘 미만이면 recommend와 같은 계획 거리 fallback."""
    gps = gps_distance_m(traces, started_at, ended_at)
    if gps is not None:
        return gps, DistanceSource.GPS
    planned = planned_walk_distance_m(leg.planned_distance_m, leg.start, leg.end)
    if planned is None:
        return None
    source = DistanceSource.PLANNED if leg.planned_distance_m is not None else DistanceSource.GREAT_CIRCLE
    return planned, source


def gps_distance_m(traces: tuple[TracePoint, ...], started_at: datetime, ended_at: datetime) -> float | None:
    """[started_at, ended_at] 안의 정확한 포인트를 시각순으로 이은 haversine 누적. 둘 미만이면 `None`.

    `accuracy_m`이 비어 있는 포인트는 남긴다 — 앱이 업로드 전에 이미 거르고(#4), 값이 없다고
    부정확하다는 뜻은 아니다.
    """
    points = sorted(
        (
            p
            for p in traces
            if started_at <= p.recorded_at <= ended_at
            and (p.accuracy_m is None or p.accuracy_m <= GPS_MAX_ACCURACY_M)
        ),
        key=lambda p: p.recorded_at,
    )
    if len(points) < 2:
        return None
    return sum(haversine_m(a.lat, a.lng, b.lat, b.lng) for a, b in pairwise(points))


def _arrived_at_stop_at(leg: TripLeg) -> datetime | None:
    """정류장에서 기다린 시간은 도보가 아니므로 역 도착은 항상 **첫 시도**에서 가져온다."""
    if leg.leg_type != "TRANSIT":
        return None
    first = next((a for a in leg.attempts if a.attempt_seq == 1), None)
    return None if first is None else first.arrived_at_stop_at


def _alighted_at(leg: TripLeg) -> datetime | None:
    """하차는 **탄 시도**(`CAUGHT`)에서 가져온다. 여럿이면(기록 오류) 마지막 시도를 믿는다."""
    if leg.leg_type != "TRANSIT":
        return None
    caught = [a for a in leg.attempts if a.result == "CAUGHT"]
    return max(caught, key=lambda a: a.attempt_seq).alighted_at if caught else None
