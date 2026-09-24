"""ALGORITHM 3절 역산 (합성 데이터). 분위수 방향이 이 파일의 주제다."""

from __future__ import annotations

import pytest

from conftest import kst
from whenioff_analytics.model.distributions import Normal, TimeNormal
from whenioff_analytics.model.recommend import (
    Leg,
    NoFeasibleVehicleError,
    TransitLeg,
    VehicleCandidate,
    WalkLeg,
    recommend_departure,
)

Z95 = 1.6448536269514722
P = 0.95


def vehicle(
    label: str, board_at: str, board_sd: float, travel_sec: float, travel_sd: float
) -> VehicleCandidate:
    board = TimeNormal(mean_at=kst(board_at), stddev_sec=board_sd)
    return VehicleCandidate(
        label=label,
        board=board,
        alight=board.shifted_by(Normal(mean=travel_sec, stddev=travel_sd)),
    )


def test_boarding_target_is_the_lower_quantile_so_an_early_vehicle_is_still_caught() -> None:
    """`Q_board(1 − p)`가 `Q_board(p)`로 뒤집히면 여기서 깨진다.

    뒤집히면 승차역 도착 목표가 차의 평균 출발보다 **늦어지고**, 그 시각에 차가 아직 있을 확률은
    p가 아니라 1 − p가 된다 (= 매일 차를 놓친다).
    """
    train = vehicle("08:30", "2026-09-22 08:30", board_sd=120.0, travel_sec=1200.0, travel_sd=0.0)
    legs: tuple[Leg, ...] = (
        TransitLeg(route_leg_id=2, candidates=(train,)),
        WalkLeg(route_leg_id=3, duration=Normal(mean=300.0, stddev=0.0)),
    )

    result = recommend_departure(legs, kst("2026-09-22 09:00"), P)
    chosen = result.chosen[0]

    assert chosen.be_at_stop_by < train.board.mean_at
    assert (train.board.mean_at - chosen.be_at_stop_by).total_seconds() == pytest.approx(120.0 * Z95)
    # 정말 잡는지: 그 시각에 차가 아직 떠나지 않았을 확률이 p여야 한다.
    assert train.board.sf(chosen.be_at_stop_by) == pytest.approx(P)
    assert result.leave_home_at == chosen.be_at_stop_by


def test_alighting_feasibility_is_the_upper_quantile_so_a_late_vehicle_is_rejected() -> None:
    """하차 판정이 `Q_alight(1 − p)`로 뒤집히면 늦은 차가 통과해 여기서 깨진다."""
    target = kst("2026-09-22 09:00")
    late = vehicle("08:30", "2026-09-22 08:30", board_sd=300.0, travel_sec=1680.0, travel_sd=0.0)
    early = vehicle("08:10", "2026-09-22 08:10", board_sd=300.0, travel_sec=1800.0, travel_sd=0.0)
    # 늦은 차는 상위 분위수로 보면 늦고(탈락), 하위 분위수로 보면 이르다(통과) — 방향 판별선
    assert late.alight.quantile(P) > target
    assert late.alight.quantile(1 - P) < target
    assert early.alight.quantile(P) < target

    result = recommend_departure((TransitLeg(route_leg_id=1, candidates=(late, early)),), target, P)

    assert [c.candidate.label for c in result.chosen] == ["08:10"]


def test_latest_feasible_vehicle_wins() -> None:
    target = kst("2026-09-22 09:00")
    candidates = tuple(
        vehicle(label, at, board_sd=60.0, travel_sec=1200.0, travel_sd=60.0)
        for label, at in (
            ("08:00", "2026-09-22 08:00"),
            ("08:20", "2026-09-22 08:20"),
            ("08:30", "2026-09-22 08:30"),
        )
    )
    result = recommend_departure((TransitLeg(route_leg_id=1, candidates=candidates),), target, P)
    assert result.chosen[0].candidate.label == "08:30"


def test_no_feasible_vehicle_raises() -> None:
    target = kst("2026-09-22 09:00")
    too_late = vehicle("08:55", "2026-09-22 08:55", board_sd=60.0, travel_sec=1200.0, travel_sd=60.0)
    with pytest.raises(NoFeasibleVehicleError) as error:
        recommend_departure((TransitLeg(route_leg_id=7, candidates=(too_late,)),), target, P)
    assert error.value.route_leg_id == 7
    assert error.value.candidate_count == 1


def test_walk_only_route_subtracts_the_upper_quantile() -> None:
    walk = Normal(mean=600.0, stddev=60.0)
    result = recommend_departure((WalkLeg(route_leg_id=1, duration=walk),), kst("2026-09-22 09:00"), P)
    expected_sec = 600.0 + 60.0 * Z95
    assert (kst("2026-09-22 09:00") - result.leave_home_at).total_seconds() == pytest.approx(expected_sec)
    assert result.buffer_seconds == round(60.0 * Z95)
    assert result.catch_probability == 1.0
    assert result.chosen == ()


def test_buffer_sums_walk_and_boarding_margins() -> None:
    train = vehicle("08:30", "2026-09-22 08:30", board_sd=120.0, travel_sec=1200.0, travel_sd=0.0)
    legs: tuple[Leg, ...] = (
        WalkLeg(route_leg_id=1, duration=Normal(mean=400.0, stddev=40.0)),
        TransitLeg(route_leg_id=2, candidates=(train,)),
        WalkLeg(route_leg_id=3, duration=Normal(mean=300.0, stddev=30.0)),
    )
    result = recommend_departure(legs, kst("2026-09-22 09:00"), P)
    assert result.buffer_seconds == round((40.0 + 30.0 + 120.0) * Z95)


def test_catch_probability_multiplies_over_transit_legs() -> None:
    legs: tuple[Leg, ...] = (
        TransitLeg(
            route_leg_id=1,
            candidates=(vehicle("07:30", "2026-09-22 07:30", 60.0, 900.0, 30.0),),
        ),
        WalkLeg(route_leg_id=2, duration=Normal(mean=120.0, stddev=10.0)),
        TransitLeg(
            route_leg_id=3,
            candidates=(vehicle("08:10", "2026-09-22 08:10", 60.0, 1500.0, 30.0),),
        ),
        WalkLeg(route_leg_id=4, duration=Normal(mean=200.0, stddev=20.0)),
    )
    result = recommend_departure(legs, kst("2026-09-22 09:00"), P)

    assert len(result.chosen) == 2
    for chosen in result.chosen:
        assert chosen.catch_probability == pytest.approx(P)
        assert chosen.arrive_probability >= P
    expected = 1.0
    for chosen in result.chosen:
        expected *= chosen.catch_probability * chosen.arrive_probability
    assert result.catch_probability == pytest.approx(expected)
    assert result.catch_probability == pytest.approx(P**2, abs=1e-6)


def test_higher_probability_means_leaving_earlier() -> None:
    legs: tuple[Leg, ...] = (
        WalkLeg(route_leg_id=1, duration=Normal(mean=400.0, stddev=40.0)),
        TransitLeg(
            route_leg_id=2,
            candidates=(vehicle("08:30", "2026-09-22 08:30", 120.0, 1200.0, 60.0),),
        ),
    )
    safer = recommend_departure(legs, kst("2026-09-22 09:00"), 0.99)
    looser = recommend_departure(legs, kst("2026-09-22 09:00"), 0.80)
    assert safer.leave_home_at < looser.leave_home_at
    assert safer.buffer_seconds > looser.buffer_seconds


def test_same_input_gives_the_same_result() -> None:
    legs: tuple[Leg, ...] = (
        WalkLeg(route_leg_id=1, duration=Normal(mean=400.0, stddev=40.0)),
        TransitLeg(
            route_leg_id=2,
            candidates=tuple(
                vehicle(label, at, 120.0, 1200.0, 60.0)
                for label, at in (("08:10", "2026-09-22 08:10"), ("08:30", "2026-09-22 08:30"))
            ),
        ),
        WalkLeg(route_leg_id=3, duration=Normal(mean=300.0, stddev=30.0)),
    )
    target = kst("2026-09-22 09:00")
    assert recommend_departure(legs, target, P) == recommend_departure(legs, target, P)


def test_naive_target_is_rejected() -> None:
    from datetime import datetime

    with pytest.raises(ValueError, match="timezone-aware"):
        recommend_departure(
            (WalkLeg(route_leg_id=1, duration=Normal(60.0, 6.0)),), datetime(2026, 9, 22, 9, 0), P
        )
