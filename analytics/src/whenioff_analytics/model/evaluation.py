"""추천 성과 평가 (#72): 그날 마지막 추천과 그날 trip을 짝지어 "추천대로 나갔을 때 됐는가"를 계산한다.

- **추천**: (경로, `target_date`, `model_version`)마다 `computed_at DESC, id DESC` 첫 행 — backend
  `RecommendationHistoryService`(#62)의 "그날 마지막으로 계산된 추천"과 같은 기준이다
- **trip**: 같은 경로의 `trip_date == target_date`인 trip 중 하나(`choose_trip`). 없으면 평가하지 않는다
- **전 구간 탑승 / 놓친 차 수**: #62의 `allLegsCaught`(경로의 TRANSIT 구간마다 `CAUGHT` 시도가 있다,
  TRANSIT이 없는 경로는 참) / `missedCount`(`MISSED` 시도 수)와 같은 정의다
- **정류장 대기**: `CAUGHT` 구간마다 탄 차의 `vehicle_actual_departure_at` − 그 구간 첫 시도
  (`attempt_seq = 1`)의 `arrived_at_stop_at`. 놓친 차를 보낸 시간까지 포함한 "정류장에 선 뒤 떠나기까지"다

모두 순수 함수다. 초 단위 차이는 calibrate와 같이 0에서 먼 쪽으로 반올림한다.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from whenioff_analytics.model.calibration import round_half_away

CAUGHT = "CAUGHT"
MISSED = "MISSED"


@dataclass(frozen=True)
class RecommendationRecord:
    """`departure_recommendations` 한 행 (평가에 필요한 열만)."""

    id: int
    commute_route_id: int
    target_date: date
    model_version: str
    target_arrival_at: datetime
    recommended_leave_home_at: datetime
    computed_at: datetime


@dataclass(frozen=True)
class AttemptRecord:
    """`boarding_attempts` 한 행."""

    route_leg_id: int
    attempt_seq: int
    result: str
    arrived_at_stop_at: datetime | None
    vehicle_actual_departure_at: datetime | None


@dataclass(frozen=True)
class TripRecord:
    id: int
    commute_route_id: int
    trip_date: date
    left_home_at: datetime | None
    arrived_destination_at: datetime | None
    attempts: tuple[AttemptRecord, ...] = ()


@dataclass(frozen=True)
class Evaluation:
    """`recommendation_evaluations` 한 행."""

    commute_route_id: int
    target_date: date
    model_version: str
    departure_recommendation_id: int
    commute_trip_id: int
    target_arrival_at: datetime
    recommended_leave_home_at: datetime
    actual_left_home_at: datetime | None
    actual_arrived_at: datetime | None
    departure_diff_sec: int | None
    arrival_diff_sec: int | None
    is_late: bool | None
    all_legs_caught: bool
    missed_count: int
    avg_stop_wait_sec: int | None

    @property
    def key(self) -> tuple[int, date, str]:
        return self.commute_route_id, self.target_date, self.model_version


@dataclass(frozen=True)
class VersionSummary:
    model_version: str
    n: int
    late: int
    with_arrival: int
    """`is_late`가 정해진(도착 기록이 있는) 평가 수. 지각률의 분모다."""
    mean_departure_diff_sec: float | None
    mean_stop_wait_sec: float | None

    @property
    def late_rate(self) -> float | None:
        return self.late / self.with_arrival if self.with_arrival else None


def latest_recommendations(recommendations: Iterable[RecommendationRecord]) -> list[RecommendationRecord]:
    """(경로, `target_date`, `model_version`)마다 `computed_at DESC, id DESC` 첫 행. 키 순으로 낸다."""
    latest: dict[tuple[int, date, str], RecommendationRecord] = {}
    for rec in recommendations:
        key = (rec.commute_route_id, rec.target_date, rec.model_version)
        current = latest.get(key)
        if current is None or (rec.computed_at, rec.id) > (current.computed_at, current.id):
            latest[key] = rec
    return [latest[key] for key in sorted(latest)]


def choose_trip(trips: Sequence[TripRecord], target_arrival_at: datetime) -> TripRecord | None:
    """그날 trip이 여럿이면 하나를 고른다.

    1. 도착 기록이 있는 trip 중 `arrived_destination_at`이 목표 도착에 가장 가까운 것
       (같으면 이른 도착, 그다음 id)
    2. 도착한 trip이 없으면 `left_home_at`이 가장 이른 것 (없는 것은 뒤로, 같으면 id)

    목표를 맞추려던 이동은 목표 근처에 도착한 이동이다. 같은 날 다른 볼일로 같은 경로를 탄 기록이
    평가를 흔들지 않게 한다.
    """
    if not trips:
        return None
    arrived = [t for t in trips if t.arrived_destination_at is not None]
    if arrived:
        return min(
            arrived,
            key=lambda t: (
                abs(_seconds(_required(t.arrived_destination_at) - target_arrival_at)),
                _required(t.arrived_destination_at),
                t.id,
            ),
        )
    return min(trips, key=lambda t: (t.left_home_at is None, t.left_home_at or target_arrival_at, t.id))


def stop_wait_sec(attempts: Iterable[AttemptRecord]) -> int | None:
    """`CAUGHT` 구간마다 (탄 차 실제 출발 − 첫 시도의 정류장 도착)의 평균, 반올림한 초.

    두 시각이 다 있는 구간만 평균한다. 그런 구간이 없으면 `None`. 음수(출발이 도착보다 앞 — geofence가
    늦게 잡힌 기록)는 대기로 볼 수 없어 뺀다. 한 구간에 `CAUGHT`가 둘 이상이면 앞 시도를 쓴다.
    """
    by_leg: dict[int, list[AttemptRecord]] = defaultdict(list)
    for attempt in attempts:
        by_leg[attempt.route_leg_id].append(attempt)
    waits: list[float] = []
    for leg_attempts in by_leg.values():
        caught = sorted((a for a in leg_attempts if a.result == CAUGHT), key=lambda a: a.attempt_seq)
        first = next((a for a in leg_attempts if a.attempt_seq == 1), None)
        if not caught or first is None:
            continue
        departed, arrived = caught[0].vehicle_actual_departure_at, first.arrived_at_stop_at
        if departed is None or arrived is None:
            continue
        wait = _seconds(departed - arrived)
        if wait >= 0:
            waits.append(wait)
    if not waits:
        return None
    return round_half_away(sum(waits) / len(waits))


def evaluate(
    recommendation: RecommendationRecord, trip: TripRecord, transit_leg_ids: Iterable[int]
) -> Evaluation:
    left, arrived, target = trip.left_home_at, trip.arrived_destination_at, recommendation.target_arrival_at
    recommended = recommendation.recommended_leave_home_at
    caught_legs = {a.route_leg_id for a in trip.attempts if a.result == CAUGHT}
    return Evaluation(
        commute_route_id=recommendation.commute_route_id,
        target_date=recommendation.target_date,
        model_version=recommendation.model_version,
        departure_recommendation_id=recommendation.id,
        commute_trip_id=trip.id,
        target_arrival_at=target,
        recommended_leave_home_at=recommended,
        actual_left_home_at=left,
        actual_arrived_at=arrived,
        departure_diff_sec=(None if left is None else round_half_away(_seconds(left - recommended))),
        arrival_diff_sec=None if arrived is None else round_half_away(_seconds(arrived - target)),
        # 반올림 전 시각으로 판정한다. 정각 도착은 지각이 아니다.
        is_late=None if arrived is None else arrived > target,
        all_legs_caught=set(transit_leg_ids) <= caught_legs,
        missed_count=sum(a.result == MISSED for a in trip.attempts),
        avg_stop_wait_sec=stop_wait_sec(trip.attempts),
    )


def evaluate_all(
    recommendations: Iterable[RecommendationRecord],
    trips: Iterable[TripRecord],
    transit_legs_by_route: Mapping[int, Iterable[int]],
) -> list[Evaluation]:
    """마지막 추천마다 그날 trip을 골라 평가한다. trip이 없는 (경로, 날짜)는 결과에 없다."""
    trips_by_day: dict[tuple[int, date], list[TripRecord]] = defaultdict(list)
    for t in trips:
        trips_by_day[(t.commute_route_id, t.trip_date)].append(t)
    evaluations = []
    for rec in latest_recommendations(recommendations):
        chosen = choose_trip(
            trips_by_day.get((rec.commute_route_id, rec.target_date), []), rec.target_arrival_at
        )
        if chosen is not None:
            evaluations.append(evaluate(rec, chosen, transit_legs_by_route.get(rec.commute_route_id, ())))
    return evaluations


def summarize(evaluations: Iterable[Evaluation]) -> list[VersionSummary]:
    """`model_version`별 n, 지각 수(분모는 도착 기록이 있는 평가), 평균 출발 차이, 평균 정류장 대기."""
    by_version: dict[str, list[Evaluation]] = defaultdict(list)
    for e in evaluations:
        by_version[e.model_version].append(e)
    summaries = []
    for version in sorted(by_version):
        rows = by_version[version]
        decided = [e.is_late for e in rows if e.is_late is not None]
        summaries.append(
            VersionSummary(
                model_version=version,
                n=len(rows),
                late=sum(decided),
                with_arrival=len(decided),
                mean_departure_diff_sec=_mean(e.departure_diff_sec for e in rows),
                mean_stop_wait_sec=_mean(e.avg_stop_wait_sec for e in rows),
            )
        )
    return summaries


def _mean(values: Iterable[int | None]) -> float | None:
    present = [v for v in values if v is not None]
    return sum(present) / len(present) if present else None


def _seconds(delta: timedelta) -> float:
    return delta.total_seconds()


def _required(moment: datetime | None) -> datetime:
    assert moment is not None
    return moment
