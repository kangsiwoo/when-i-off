"""DB에서 읽은 경로를 분포로 바꿔 역산에 넘기고, 결과를 기록하는 조립 계층."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time, timedelta

from whenioff_analytics import defaults
from whenioff_analytics.daytype import kst_date_of, kst_time_of, resolve_day_type
from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.line_stops import resolve_leg_direction
from whenioff_analytics.io.recommendations import RecommendationRow, SaveOutcome, save_recommendation
from whenioff_analytics.io.routes import CommuteRoute, RouteLeg, load_route
from whenioff_analytics.io.schedules import load_scheduled_departures
from whenioff_analytics.io.signals import CycleBand, load_signal_cycles, select_cycle
from whenioff_analytics.model.distributions import Moments, Normal, TimeNormal, haversine_m, walk_time
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
class RecommendationResult:
    route: CommuteRoute
    target_arrival_at: datetime
    probability: float
    legs: tuple[Leg, ...]
    recommendation: Recommendation


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
    legs = _build_legs(conn, route, target_arrival_at, lookback_hours)
    recommendation = recommend_departure(legs, target_arrival_at, probability)
    return RecommendationResult(
        route=route,
        target_arrival_at=target_arrival_at,
        probability=probability,
        legs=legs,
        recommendation=recommendation,
    )


def store(conn: Connection, result: RecommendationResult) -> tuple[SaveOutcome, int]:
    recommendation = result.recommendation
    row = RecommendationRow(
        user_id=result.route.user_id,
        commute_route_id=result.route.id,
        target_date=kst_date_of(result.target_arrival_at),
        target_arrival_at=result.target_arrival_at,
        # 초 미만은 버린다. 버리는 방향이 "더 일찍 나간다"라서 안전한 쪽이다.
        recommended_leave_home_at=recommendation.leave_home_at.replace(microsecond=0),
        catch_probability=recommendation.catch_probability,
        buffer_seconds=recommendation.buffer_seconds,
        model_version=defaults.MODEL_VERSION,
    )
    return save_recommendation(conn, row)


def _build_legs(
    conn: Connection,
    route: CommuteRoute,
    target_arrival_at: datetime,
    lookback_hours: float,
) -> tuple[Leg, ...]:
    # 신호 주기는 요일유형 × 시간대별이라 "언제 그 횡단보도에 서는가"가 필요하지만, 그 시각은
    # 역산이 끝나야 나온다. v1은 목표 도착 시각의 시간대로 한 번에 고른다 — 통근 한 번은
    # 시간대 하나에 대체로 들어가고, 어긋나도 주기 모델의 기대 대기(수십 초) 차이라서 작다.
    day_type = resolve_day_type(kst_date_of(target_arrival_at))
    band_time = kst_time_of(target_arrival_at)
    signal_ids = [c.traffic_signal_id for leg in route.legs for c in leg.crossings]
    cycles = load_signal_cycles(conn, signal_ids, day_type)

    legs: list[Leg] = []
    for leg in route.legs:
        if leg.leg_type == "WALK":
            waits = tuple(
                _crossing_wait(cycles, crossing.traffic_signal_id, band_time) for crossing in leg.crossings
            )
            legs.append(
                WalkLeg(
                    route_leg_id=leg.id,
                    duration=walk_time(_walk_distance_m(leg), defaults.WALKING_SPEED, waits),
                )
            )
        else:
            legs.append(_transit_leg(conn, leg, target_arrival_at, lookback_hours))
    return tuple(legs)


def _crossing_wait(
    cycles: dict[int, tuple[CycleBand, ...]], traffic_signal_id: int, band_time: time
) -> Moments:
    """실시간 신호 상태(ALGORITHM 2.1(b))가 들어올 자리. 지금은 주기 모델뿐이다."""
    cycle = select_cycle(cycles.get(traffic_signal_id, ()), band_time) or defaults.DEFAULT_SIGNAL_CYCLE
    return cycle.wait()


def _walk_distance_m(leg: RouteLeg) -> float:
    """실측(`walking_segments`)은 아직 없다. 계획 거리, 없으면 구간 양 끝 좌표의 대권거리."""
    if leg.planned_distance_m is not None:
        return leg.planned_distance_m
    if leg.start_lat is None or leg.start_lng is None or leg.end_lat is None or leg.end_lng is None:
        raise IncompleteLegError(leg.id, "WALK leg has neither planned_distance_m nor endpoints")
    return haversine_m(leg.start_lat, leg.start_lng, leg.end_lat, leg.end_lng)


def _transit_leg(
    conn: Connection,
    leg: RouteLeg,
    target_arrival_at: datetime,
    lookback_hours: float,
) -> TransitLeg:
    if leg.transit_line_id is None or leg.board_stop_id is None or leg.planned_travel_sec is None:
        raise IncompleteLegError(leg.id, "TRANSIT leg is missing line, stop or planned_travel_sec")

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

    travel = Normal(
        mean=float(leg.planned_travel_sec),
        stddev=float(leg.planned_travel_sec) * defaults.TRAVEL_TIME_CV,
    )
    candidates = []
    for departure in departures:
        board = TimeNormal(
            mean_at=departure.departure_at + timedelta(seconds=defaults.PREDICTION_BIAS_SEC),
            stddev_sec=defaults.PREDICTION_STDDEV_SEC,
        )
        candidates.append(
            VehicleCandidate(
                label=f"{departure.service_date} {departure.scheduled_time.isoformat()} KST",
                board=board,
                alight=board.shifted_by(travel),
            )
        )
    return TransitLeg(route_leg_id=leg.id, candidates=tuple(candidates))


__all__ = [
    "IncompleteLegError",
    "NoCandidateVehiclesError",
    "RecommendationResult",
    "compute_recommendation",
    "store",
]
