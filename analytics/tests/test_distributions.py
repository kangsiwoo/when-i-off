"""ALGORITHM 2절 분포들의 수치 검증 (합성 데이터)."""

from __future__ import annotations

import math

import pytest

from conftest import kst
from whenioff_analytics.model.distributions import (
    Normal,
    SignalCycle,
    TimeNormal,
    haversine_m,
    walk_time,
)

Z95 = 1.6448536269514722


def test_normal_quantile_matches_standard_z() -> None:
    assert Normal(mean=100.0, stddev=10.0).quantile(0.95) == pytest.approx(100 + 10 * Z95)
    assert Normal(mean=100.0, stddev=10.0).quantile(0.05) == pytest.approx(100 - 10 * Z95)
    assert Normal(mean=100.0, stddev=0.0).quantile(0.95) == 100.0


def test_cycle_wait_moments_match_closed_form() -> None:
    cycle = SignalCycle(cycle_sec=120.0, red_sec=90.0)
    wait = cycle.wait()
    assert wait.mean == pytest.approx(90.0**2 / (2 * 120.0))  # 33.75s
    assert wait.variance == pytest.approx(90.0**3 / (3 * 120.0) - wait.mean**2)


def test_cycle_wait_is_zero_when_red_vanishes() -> None:
    wait = SignalCycle(cycle_sec=120.0, red_sec=0.001).wait()
    assert wait.mean == pytest.approx(0.0, abs=1e-6)
    assert wait.variance == pytest.approx(0.0, abs=1e-6)


def test_cycle_rejects_red_longer_than_cycle() -> None:
    with pytest.raises(ValueError, match="red_sec"):
        SignalCycle(cycle_sec=100.0, red_sec=100.0)


def test_walk_time_uses_delta_method() -> None:
    walk = walk_time(600.0, Normal(mean=1.2, stddev=0.15))
    assert walk.mean == pytest.approx(500.0)
    assert walk.stddev == pytest.approx(600.0 * 0.15 / 1.2**2)  # 62.5s


def test_walk_time_adds_signal_waits_in_mean_and_variance() -> None:
    waits = (SignalCycle(cycle_sec=120.0, red_sec=90.0).wait(),) * 2
    plain = walk_time(600.0, Normal(mean=1.2, stddev=0.15))
    crossed = walk_time(600.0, Normal(mean=1.2, stddev=0.15), waits)
    assert crossed.mean == pytest.approx(plain.mean + 2 * waits[0].mean)
    assert crossed.stddev == pytest.approx(math.sqrt(plain.stddev**2 + 2 * waits[0].variance))


def test_time_normal_quantile_and_tail_probabilities_agree() -> None:
    dist = TimeNormal(mean_at=kst("2026-09-22 08:30"), stddev_sec=120.0)
    early = dist.quantile(0.05)
    assert (dist.mean_at - early).total_seconds() == pytest.approx(120.0 * Z95)
    assert dist.sf(early) == pytest.approx(0.95)
    assert dist.cdf(dist.quantile(0.95)) == pytest.approx(0.95)


def test_time_normal_shift_adds_variance() -> None:
    board = TimeNormal(mean_at=kst("2026-09-22 08:30"), stddev_sec=90.0)
    alight = board.shifted_by(Normal(mean=1200.0, stddev=120.0))
    assert alight.mean_at == kst("2026-09-22 08:50")
    assert alight.stddev_sec == pytest.approx(math.hypot(90.0, 120.0))


def test_haversine_matches_known_distance() -> None:
    # GTX-A 동탄 ~ 수서 (직선거리 약 32km)
    assert haversine_m(37.201167, 127.095111, 37.48694, 127.10194) == pytest.approx(31_800, rel=0.02)


def test_invalid_inputs_are_rejected() -> None:
    with pytest.raises(ValueError, match="probability"):
        Normal(mean=1.0, stddev=1.0).quantile(1.0)
    with pytest.raises(ValueError, match="stddev"):
        Normal(mean=1.0, stddev=-1.0)
    with pytest.raises(ValueError, match="speed"):
        walk_time(100.0, Normal(mean=0.0, stddev=0.1))
