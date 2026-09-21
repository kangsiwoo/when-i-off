"""ALGORITHM.md 3절의 역산. 경로의 분포만 받고 DB도 시계도 모른다."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from whenioff_analytics.model.distributions import Normal, TimeNormal


@dataclass(frozen=True)
class WalkLeg:
    route_leg_id: int
    duration: Normal


@dataclass(frozen=True)
class VehicleCandidate:
    """후보 차량 한 대. `label`은 사람이 읽는 식별자(시간표 시각 등)일 뿐 계산에 쓰지 않는다."""

    label: str
    board: TimeNormal
    alight: TimeNormal


@dataclass(frozen=True)
class TransitLeg:
    route_leg_id: int
    candidates: tuple[VehicleCandidate, ...]


Leg = WalkLeg | TransitLeg


@dataclass(frozen=True)
class ChosenVehicle:
    route_leg_id: int
    candidate: VehicleCandidate
    be_at_stop_by: datetime
    needed_at: datetime
    catch_probability: float
    arrive_probability: float


@dataclass(frozen=True)
class Recommendation:
    leave_home_at: datetime
    catch_probability: float
    buffer_seconds: int
    chosen: tuple[ChosenVehicle, ...]


class NoFeasibleVehicleError(Exception):
    """주어진 후보 중 목표 시각을 맞추는 차량이 없을 때.

    ALGORITHM 3절은 "창을 앞으로 넓혀 재시도"라고 쓰지만, 호출자가 애초에 목표 시각 이전
    구간을 통째로 실어 오고 여기서 그중 가장 늦은 가능 차를 고르므로 결과는 같다. 이 예외는
    그 구간 전체에 가능한 차가 없다는 뜻이다.
    """

    def __init__(self, route_leg_id: int, needed_at: datetime, candidate_count: int) -> None:
        super().__init__(
            f"route_leg {route_leg_id}: none of {candidate_count} candidate vehicles "
            f"arrives by {needed_at.isoformat()}"
        )
        self.route_leg_id = route_leg_id
        self.needed_at = needed_at
        self.candidate_count = candidate_count


def recommend_departure(
    legs: tuple[Leg, ...],
    target_arrival_at: datetime,
    p: float = 0.95,
) -> Recommendation:
    """목표 도착 시각에서 거꾸로 짚어 집을 나설 시각을 구한다.

    `p`는 구간마다 독립적으로 적용된다 (ALGORITHM 3절). TRANSIT 구간이 k개면 전체 성공확률은
    대략 p^k이고, 그 곱을 `catch_probability`로 기록한다.
    """
    if target_arrival_at.tzinfo is None:
        raise ValueError("target_arrival_at must be timezone-aware")
    if not legs:
        raise ValueError("route has no legs")

    needed_at = target_arrival_at
    buffer_sec = 0.0
    probability = 1.0
    chosen: list[ChosenVehicle] = []

    for leg in reversed(legs):
        match leg:
            case WalkLeg():
                quantile = leg.duration.quantile(p)
                buffer_sec += quantile - leg.duration.mean
                needed_at -= timedelta(seconds=quantile)
            case TransitLeg():
                feasible = [c for c in leg.candidates if c.alight.quantile(p) <= needed_at]
                if not feasible:
                    raise NoFeasibleVehicleError(leg.route_leg_id, needed_at, len(leg.candidates))
                vehicle = max(feasible, key=lambda c: c.board.mean_at)
                # 하차 판정은 상위 분위수(차가 늦게 올 경우 대비), 승차역 도착 목표는 하위 분위수다.
                # 차가 평소보다 일찍 올 수 있으니 그만큼 일찍 가 있어야 p 확률로 탄다.
                be_at_stop_by = vehicle.board.quantile(1 - p)
                catch_probability = vehicle.board.sf(be_at_stop_by)
                arrive_probability = vehicle.alight.cdf(needed_at)
                buffer_sec += (vehicle.board.mean_at - be_at_stop_by).total_seconds()
                probability *= catch_probability * arrive_probability
                chosen.append(
                    ChosenVehicle(
                        route_leg_id=leg.route_leg_id,
                        candidate=vehicle,
                        be_at_stop_by=be_at_stop_by,
                        needed_at=needed_at,
                        catch_probability=catch_probability,
                        arrive_probability=arrive_probability,
                    )
                )
                needed_at = be_at_stop_by

    return Recommendation(
        leave_home_at=needed_at,
        catch_probability=probability,
        buffer_seconds=round(buffer_sec),
        chosen=tuple(reversed(chosen)),
    )
