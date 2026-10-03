"""DB에서 읽은 경로를 분포로 바꿔 역산에 넘기고, 결과를 기록하는 조립 계층."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time, timedelta

from whenioff_analytics import defaults
from whenioff_analytics.daytype import kst_date_of, kst_time_of, resolve_day_type
from whenioff_analytics.io.calibration import (
    load_prediction_rows,
    load_travel_time_rows,
    load_walking_profiles_for_user,
)
from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.line_stops import resolve_leg_direction
from whenioff_analytics.io.recommendations import RecommendationRow, SaveOutcome, save_recommendation
from whenioff_analytics.io.routes import CommuteRoute, RouteLeg, load_route
from whenioff_analytics.io.schedules import load_scheduled_departures
from whenioff_analytics.io.signals import CycleBand, ResolvedCycle, load_signal_cycles, resolve_cycle
from whenioff_analytics.model.calibration import WalkingProfile
from whenioff_analytics.model.distributions import planned_walk_distance_m, walk_time
from whenioff_analytics.model.lookup import (
    Resolved,
    ResolvedCandidate,
    min_sample_count,
    resolve_candidate,
    resolve_walking_speed,
)
from whenioff_analytics.model.recommend import (
    Leg,
    Recommendation,
    TransitLeg,
    VehicleCandidate,
    WalkLeg,
    recommend_departure,
)


class NoCandidateVehiclesError(Exception):
    def __init__(self, route_leg_id: int, window_start: datetime, window_end: datetime) -> None:
        super().__init__(
            f"route_leg {route_leg_id}: no scheduled departure between "
            f"{window_start.isoformat()} and {window_end.isoformat()}"
        )
        self.route_leg_id = route_leg_id


class IncompleteLegError(Exception):
    def __init__(self, route_leg_id: int, reason: str) -> None:
        super().__init__(f"route_leg {route_leg_id}: {reason}")
        self.route_leg_id = route_leg_id


@dataclass(frozen=True)
class CrossingInput:
    """WALK 구간이 건너는 교차로 하나에 쓴 신호 주기와 그 출처 (#78)."""

    traffic_signal_id: int
    resolved: ResolvedCycle


@dataclass(frozen=True)
class LegInputs:
    """구간 하나의 입력 출처.

    WALK는 `walking_speed`와 `crossings`(교차로 순서), TRANSIT은 `candidates`(후보 순서 그대로).
    """

    route_leg_id: int
    walking_speed: Resolved | None = None
    candidates: tuple[ResolvedCandidate, ...] = ()
    crossings: tuple[CrossingInput, ...] = ()


@dataclass(frozen=True)
class RecommendationResult:
    route: CommuteRoute
    target_arrival_at: datetime
    probability: float
    legs: tuple[Leg, ...]
    inputs: tuple[LegInputs, ...]
    """`legs`와 같은 순서."""
    recommendation: Recommendation

    def candidate_inputs(self, route_leg_id: int, candidate: VehicleCandidate) -> ResolvedCandidate:
        for leg, inputs in zip(self.legs, self.inputs, strict=True):
            if isinstance(leg, TransitLeg) and leg.route_leg_id == route_leg_id:
                return inputs.candidates[leg.candidates.index(candidate)]
        raise KeyError(route_leg_id)

    def min_transit_sample_count(self) -> int | None:
        """고른 차량들의 입력 표본 수 중 최솟값 (#86). TRANSIT 구간이 없으면 `None`."""
        return min_sample_count(
            self.candidate_inputs(chosen.route_leg_id, chosen.candidate)
            for chosen in self.recommendation.chosen
        )


def compute_recommendation(
    conn: Connection,
    route_id: int,
    target_arrival_at: datetime,
    probability: float = defaults.DEFAULT_PROBABILITY,
    lookback_hours: float = defaults.DEFAULT_LOOKBACK_HOURS,
) -> RecommendationResult:
    route = load_route(conn, route_id)
    if not route.legs:
        raise IncompleteLegError(route_id, "commute route has no legs")
    built = _build_legs(conn, route, target_arrival_at, lookback_hours)
    legs = tuple(leg for leg, _ in built)
    recommendation = recommend_departure(legs, target_arrival_at, probability)
    return RecommendationResult(
        route=route,
        target_arrival_at=target_arrival_at,
        probability=probability,
        legs=legs,
        inputs=tuple(inputs for _, inputs in built),
        recommendation=recommendation,
    )


def store(conn: Connection, result: RecommendationResult) -> tuple[SaveOutcome, int]:
    return save_recommendation(conn, recommendation_row(result))


def recommendation_row(result: RecommendationResult) -> RecommendationRow:
    recommendation = result.recommendation
    return RecommendationRow(
        user_id=result.route.user_id,
        commute_route_id=result.route.id,
        target_date=kst_date_of(result.target_arrival_at),
        target_arrival_at=result.target_arrival_at,
        # 초 미만은 버린다. 버리는 방향이 "더 일찍 나간다"라서 안전한 쪽이다.
        recommended_leave_home_at=recommendation.leave_home_at.replace(microsecond=0),
        catch_probability=recommendation.catch_probability,
        buffer_seconds=recommendation.buffer_seconds,
        model_version=defaults.MODEL_VERSION,
        min_transit_sample_count=result.min_transit_sample_count(),
    )


def _build_legs(
    conn: Connection,
    route: CommuteRoute,
    target_arrival_at: datetime,
    lookback_hours: float,
) -> list[tuple[Leg, LegInputs]]:
    # 신호 주기는 요일유형 × 시간대별이라 "언제 그 횡단보도에 서는가"가 필요하지만, 그 시각은
    # 역산이 끝나야 나온다. 지금은 목표 도착 시각의 시간대로 한 번에 고른다 — 통근 한 번은
    # 시간대 하나에 대체로 들어가고, 어긋나도 주기 모델의 기대 대기(수십 초) 차이라서 작다.
    day_type = resolve_day_type(kst_date_of(target_arrival_at))
    band_time = kst_time_of(target_arrival_at)
    signal_ids = [c.traffic_signal_id for leg in route.legs for c in leg.crossings]
    cycles = load_signal_cycles(conn, signal_ids, day_type)
    profiles = load_walking_profiles_for_user(conn, route.user_id)

    legs: list[tuple[Leg, LegInputs]] = []
    for leg in route.legs:
        if leg.leg_type == "WALK":
            legs.append(_walk_leg(leg, profiles, cycles, band_time))
        else:
            legs.append(_transit_leg(conn, leg, target_arrival_at, lookback_hours))
    return legs


def _walk_leg(
    leg: RouteLeg,
    profiles: list[WalkingProfile],
    cycles: dict[int, tuple[CycleBand, ...]],
    band_time: time,
) -> tuple[WalkLeg, LegInputs]:
    crossings = tuple(
        CrossingInput(c.traffic_signal_id, _crossing_cycle(cycles, c.traffic_signal_id, band_time))
        for c in leg.crossings
    )
    waits = tuple(c.resolved.cycle.wait() for c in crossings)
    speed = resolve_walking_speed(profiles, leg.id)
    walk = WalkLeg(route_leg_id=leg.id, duration=walk_time(_walk_distance_m(leg), speed.value, waits))
    return walk, LegInputs(route_leg_id=leg.id, walking_speed=speed, crossings=crossings)


def _crossing_cycle(
    cycles: dict[int, tuple[CycleBand, ...]], traffic_signal_id: int, band_time: time
) -> ResolvedCycle:
    """실시간 신호 상태(ALGORITHM 2.1(b))가 들어올 자리. 지금은 주기 모델뿐이다."""
    return resolve_cycle(cycles.get(traffic_signal_id, ()), band_time)


def _walk_distance_m(leg: RouteLeg) -> float:
    """실측(`walking_segments`)은 아직 쓰지 않는다. 계획 거리, 없으면 구간 양 끝 좌표의 대권거리."""
    distance = planned_walk_distance_m(leg.planned_distance_m, leg.start, leg.end)
    if distance is None:
        raise IncompleteLegError(leg.id, "WALK leg has neither planned_distance_m nor endpoints")
    return distance


def _transit_leg(
    conn: Connection,
    leg: RouteLeg,
    target_arrival_at: datetime,
    lookback_hours: float,
) -> tuple[TransitLeg, LegInputs]:
    if (
        leg.transit_line_id is None
        or leg.board_stop_id is None
        or leg.alight_stop_id is None
        or leg.planned_travel_sec is None
    ):
        raise IncompleteLegError(leg.id, "TRANSIT leg is missing line, stops or planned_travel_sec")

    # 같은 정류장에 상·하행이 같이 서므로 방향을 먼저 정한다. 안 그러면 반대 방향 차가 후보에
    # 섞여 출발 시각이 통째로 틀린다 (#26).
    direction_code = resolve_leg_direction(conn, leg.transit_line_id, leg.board_stop_id, leg.alight_stop_id)
    if direction_code is None:
        raise IncompleteLegError(leg.id, "transit_line_stops has no board stop row: direction unknown")

    # 후보를 목표 시각에서 lookback_hours만큼 거슬러 통째로 실어 온다. 역산이 그중 조건을
    # 만족하는 가장 늦은 차를 고르므로 "없으면 한 대 앞으로"가 창 안에서 저절로 해결된다.
    window_start = target_arrival_at - timedelta(hours=lookback_hours)
    departures = load_scheduled_departures(
        conn, leg.transit_line_id, leg.board_stop_id, direction_code, window_start, target_arrival_at
    )
    if not departures:
        raise NoCandidateVehiclesError(leg.id, window_start, target_arrival_at)

    # 보정 행은 구간마다 한 번 읽고, 차량마다 그 차의 day_type·밴드로 메모리에서 고른다.
    prediction_rows = load_prediction_rows(conn, leg.transit_line_id, leg.board_stop_id)
    travel_rows = load_travel_time_rows(conn, leg.transit_line_id, leg.board_stop_id, leg.alight_stop_id)

    resolved = tuple(
        resolve_candidate(
            label=f"{departure.service_date} {departure.scheduled_time.isoformat()} KST",
            predicted_at=departure.departure_at,
            prediction_rows=prediction_rows,
            travel_rows=travel_rows,
            planned_travel_sec=float(leg.planned_travel_sec),
        )
        for departure in departures
    )
    transit = TransitLeg(route_leg_id=leg.id, candidates=tuple(r.candidate for r in resolved))
    return transit, LegInputs(route_leg_id=leg.id, candidates=resolved)


__all__ = [
    "IncompleteLegError",
    "LegInputs",
    "NoCandidateVehiclesError",
    "RecommendationResult",
    "compute_recommendation",
    "recommendation_row",
    "store",
]
