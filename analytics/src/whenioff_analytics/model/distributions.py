"""ALGORITHM.md 2절의 구간별 분포. 모든 분포는 정규분포 근사이고 구간끼리 독립이라고 본다."""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timedelta
from statistics import NormalDist

_STANDARD = NormalDist()


def _z(p: float) -> float:
    if not 0.0 < p < 1.0:
        raise ValueError(f"probability must be in (0, 1), got {p}")
    return _STANDARD.inv_cdf(p)


@dataclass(frozen=True)
class Normal:
    """소요시간(초)이나 속도(m/s) 같은 스칼라 분포."""

    mean: float
    stddev: float

    def __post_init__(self) -> None:
        if self.stddev < 0:
            raise ValueError(f"stddev must be >= 0, got {self.stddev}")

    def quantile(self, p: float) -> float:
        z = _z(p)
        return self.mean + z * self.stddev


@dataclass(frozen=True)
class Moments:
    """분포 형태를 정하지 않고 합성만 할 때 쓰는 (평균, 분산)."""

    mean: float
    variance: float


@dataclass(frozen=True)
class TimeNormal:
    """절대 시각의 분포 (차량이 역에 실제로 있는 시각 등)."""

    mean_at: datetime
    stddev_sec: float

    def __post_init__(self) -> None:
        if self.mean_at.tzinfo is None:
            raise ValueError("mean_at must be timezone-aware")
        if self.stddev_sec < 0:
            raise ValueError(f"stddev_sec must be >= 0, got {self.stddev_sec}")

    def quantile(self, p: float) -> datetime:
        return self.mean_at + timedelta(seconds=_z(p) * self.stddev_sec)

    def shifted_by(self, duration: Normal) -> TimeNormal:
        """독립인 소요시간을 더한다 (분산은 합)."""
        return TimeNormal(
            mean_at=self.mean_at + timedelta(seconds=duration.mean),
            stddev_sec=math.hypot(self.stddev_sec, duration.stddev),
        )

    def cdf(self, at: datetime) -> float:
        """P(X <= at)."""
        delta = (at - self.mean_at).total_seconds()
        if self.stddev_sec == 0:
            return 1.0 if delta >= 0 else 0.0
        return NormalDist(0.0, self.stddev_sec).cdf(delta)

    def sf(self, at: datetime) -> float:
        """P(X >= at)."""
        return 1.0 - self.cdf(at)


@dataclass(frozen=True)
class SignalCycle:
    """보행 신호의 주기 모델 (ALGORITHM 2.1(a))."""

    cycle_sec: float
    red_sec: float

    def __post_init__(self) -> None:
        if not 0 < self.red_sec < self.cycle_sec:
            raise ValueError(f"need 0 < red_sec < cycle_sec, got {self.red_sec} / {self.cycle_sec}")

    def wait(self) -> Moments:
        """보행자가 주기 안의 임의 시점에 균등하게 도착한다고 볼 때의 대기시간."""
        mean = self.red_sec**2 / (2 * self.cycle_sec)
        second_moment = self.red_sec**3 / (3 * self.cycle_sec)
        return Moments(mean=mean, variance=second_moment - mean**2)


def walk_time(distance_m: float, speed: Normal, waits: tuple[Moments, ...] = ()) -> Normal:
    """거리/속도의 delta method 근사 + 횡단 대기의 합 (ALGORITHM 2.1)."""
    if distance_m < 0:
        raise ValueError(f"distance must be >= 0, got {distance_m}")
    if speed.mean <= 0:
        raise ValueError(f"walking speed must be > 0, got {speed.mean}")
    pure_mean = distance_m / speed.mean
    pure_stddev = distance_m * speed.stddev / speed.mean**2
    mean = pure_mean + sum(w.mean for w in waits)
    variance = pure_stddev**2 + sum(w.variance for w in waits)
    return Normal(mean=mean, stddev=math.sqrt(variance))


EARTH_RADIUS_M = 6_371_008.8


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """`planned_distance_m`이 비어 있는 WALK 구간의 거리 fallback."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    d_lat = p2 - p1
    d_lng = math.radians(lng2 - lng1)
    a = math.sin(d_lat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(d_lng / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))
