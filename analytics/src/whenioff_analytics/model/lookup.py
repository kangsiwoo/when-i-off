"""보정 테이블의 행에서 recommend의 입력 분포를 고른다 (ALGORITHM 2.2·5절, #46).

DB는 모른다 — `io/calibration.py`가 구간마다 한 번 읽어 온 행을 받아, 후보 차량마다 메모리에서
고른다. 차량마다 KST `day_type`·30분 밴드가 달라 같은 구간에서도 쓰는 행이 다르기 때문이다.

조회 순서 (샘플이 `MIN_CALIBRATION_SAMPLES` 미만이면 다음 단계로 내려간다):

- 도보 속도: 구간 행 → 사용자 전역 행(`route_leg_id` NULL) → `defaults.WALKING_SPEED`
- 예측 오차: (노선, 승차 정류장, day_type, 밴드) → 같은 노선·정류장의 모든 행을 합친(pool) 값
  → (0, 90초)
- 차내 시간: (노선, 승차역, 하차역, day_type, 밴드) → 같은 노선·역 쌍의 모든 행을 합친 값
  → `planned_travel_sec`, σ = 평균의 15%

쓰인 값마다 출처(`Provenance`)를 붙여 돌려준다. 모든 σ는 calibrate와 같은 하한 아래로 내리지 않는다
— calibrate가 쓴 행에는 이미 지켜져 있고, 합친 σ는 하한 근처 그룹들에서 그 아래로 떨어질 수 있다.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from enum import StrEnum

from whenioff_analytics import defaults
from whenioff_analytics.model.calibration import (
    PREDICTION_STDDEV_FLOOR_SEC,
    TRAVEL_TIME_STDDEV_FLOOR_SEC,
    WALKING_STDDEV_FLOOR_MPS,
    BandKey,
    PredictionCalibration,
    TravelTimeCalibration,
    WalkingProfile,
    band_key_of,
)
from whenioff_analytics.model.distributions import Normal, TimeNormal
from whenioff_analytics.model.recommend import VehicleCandidate

MIN_CALIBRATION_SAMPLES = 5
"""이 수 이상의 샘플이 있는 행(또는 합친 그룹)만 쓴다. 그보다 적으면 한 단계 위로 상속한다."""


class Provenance(StrEnum):
    CALIBRATED = "calibrated"
    """가장 좁은 키의 행을 그대로 썼다 (도보: 구간 행)."""
    INHERITED = "inherited"
    """상위 그룹 값을 썼다 (도보: 전역 행, 예측 오차·차내 시간: 노선 단위로 합친 값)."""
    DEFAULT = "default"
    """콜드스타트 기본값 (`defaults.py`)."""


@dataclass(frozen=True)
class Resolved:
    """고른 분포와 그 출처. `sample_count`는 그 값 뒤에 있는 샘플 수다 (기본값이면 0)."""

    value: Normal
    provenance: Provenance
    sample_count: int


@dataclass(frozen=True)
class SampleStats:
    count: int
    mean: float
    stddev: float


def pool_stats(groups: Iterable[SampleStats]) -> SampleStats | None:
    """그룹별 (n, 평균, 표본표준편차)를 모든 샘플을 한데 모은 (N, 평균, 표본표준편차)로 합친다.

    σ의 평균이 아니다. 전체 제곱합 = 그룹 안 제곱합 Σ(nᵢ − 1)sᵢ² + 그룹 사이 제곱합 Σnᵢ(mᵢ − M)²이고
    이를 N − 1로 나눈다 — 샘플을 직접 모아 `statistics.stdev`로 계산한 값과 같다. n = 1 그룹은 그룹 안
    항이 0이라, 그 행에 저장된 σ(샘플 1개라 넣어 둔 콜드스타트 값)는 결과에 섞이지 않는다.

    그룹이 없으면 `None`. 합쳐도 샘플이 1개뿐이면 σ가 정의되지 않으므로 그 그룹의 σ를 그대로 둔다.
    """
    nonempty = [g for g in groups if g.count > 0]
    total = sum(g.count for g in nonempty)
    if total == 0:
        return None
    mean = sum(g.count * g.mean for g in nonempty) / total
    if total == 1:
        return SampleStats(count=1, mean=mean, stddev=nonempty[0].stddev)
    within = sum((g.count - 1) * g.stddev**2 for g in nonempty)
    between = sum(g.count * (g.mean - mean) ** 2 for g in nonempty)
    return SampleStats(count=total, mean=mean, stddev=math.sqrt((within + between) / (total - 1)))


# ---------------------------------------------------------------------------
# 도보 속도
# ---------------------------------------------------------------------------


def resolve_walking_speed(profiles: Sequence[WalkingProfile], route_leg_id: int) -> Resolved:
    """`profiles`는 그 사용자의 행들(구간 행 + 전역 행). 전역 행은 calibrate가 모든 구간에서 최근 30개를
    따로 골라 낸 값이라 구간 행을 다시 합치지 않는다.
    """
    levels = (
        (route_leg_id, Provenance.CALIBRATED),
        (None, Provenance.INHERITED),
    )
    for key, provenance in levels:
        for profile in profiles:
            if profile.route_leg_id == key and profile.sample_count >= MIN_CALIBRATION_SAMPLES:
                speed = Normal(
                    mean=profile.avg_speed_mps,
                    stddev=max(profile.stddev_speed_mps, WALKING_STDDEV_FLOOR_MPS),
                )
                return Resolved(speed, provenance, profile.sample_count)
    return Resolved(defaults.WALKING_SPEED, Provenance.DEFAULT, 0)


# ---------------------------------------------------------------------------
# 예측 오차 / 차내 시간
# ---------------------------------------------------------------------------


def resolve_prediction_error(rows: Sequence[PredictionCalibration], band: BandKey) -> Resolved:
    """`rows`는 그 (노선, 승차 정류장)의 모든 행, `band`는 후보 차량의 예측 시각으로 구한 키.

    값은 `Normal(mean=bias, stddev=σ)`(초)다.
    """
    stats = [(r.band, SampleStats(r.sample_count, float(r.bias_sec), float(r.stddev_sec))) for r in rows]
    return _resolve(
        stats,
        band,
        PREDICTION_STDDEV_FLOOR_SEC,
        Normal(mean=defaults.PREDICTION_BIAS_SEC, stddev=defaults.PREDICTION_STDDEV_SEC),
    )


def resolve_travel_time(
    rows: Sequence[TravelTimeCalibration], band: BandKey, planned_travel_sec: float
) -> Resolved:
    """`rows`는 그 (노선, 승차역, 하차역)의 모든 행, `band`는 후보 차량의 출발 시각으로 구한 키."""
    stats = [(r.band, SampleStats(r.sample_count, float(r.mean_sec), float(r.stddev_sec))) for r in rows]
    return _resolve(
        stats,
        band,
        TRAVEL_TIME_STDDEV_FLOOR_SEC,
        Normal(mean=planned_travel_sec, stddev=planned_travel_sec * defaults.TRAVEL_TIME_CV),
    )


def _resolve(
    rows: Sequence[tuple[BandKey, SampleStats]], band: BandKey, floor: float, default: Normal
) -> Resolved:
    # 밴드 끝값은 시작값으로 정해지므로(LAST_BAND_END 포함) 시작값만 맞춘다.
    for key, stats in rows:
        if (
            key.day_type == band.day_type
            and key.time_band_start == band.time_band_start
            and stats.count >= MIN_CALIBRATION_SAMPLES
        ):
            return Resolved(Normal(stats.mean, max(stats.stddev, floor)), Provenance.CALIBRATED, stats.count)
    pooled = pool_stats(stats for _, stats in rows)
    if pooled is not None and pooled.count >= MIN_CALIBRATION_SAMPLES:
        return Resolved(Normal(pooled.mean, max(pooled.stddev, floor)), Provenance.INHERITED, pooled.count)
    return Resolved(default, Provenance.DEFAULT, 0)


@dataclass(frozen=True)
class ResolvedCandidate:
    """후보 차량과, 그 차에 쓴 예측 오차 `N(bias, σ)`·차내 시간 `N(mean, σ)`(초)의 출처."""

    candidate: VehicleCandidate
    prediction_error: Resolved
    travel_time: Resolved


def resolve_candidate(
    label: str,
    predicted_at: datetime,
    prediction_rows: Sequence[PredictionCalibration],
    travel_rows: Sequence[TravelTimeCalibration],
    planned_travel_sec: float,
    holidays: frozenset[date] | None = None,
) -> ResolvedCandidate:
    """후보 차량 한 대의 분포를 그 차의 시각으로 고른 보정값으로 만든다.

    키는 calibrate가 그룹을 만든 기준과 같다 — 예측 오차는 **예측(시간표) 시각**의 KST day_type·밴드,
    차내 시간은 **실제 출발 시각**의 KST day_type·밴드. 출발 시각은 아직 모르므로 그 기댓값(예측 +
    고른 bias)을 쓴다. 자정 근처 차는 KST 날짜가 바뀌어 day_type도 바뀐다 (`band_key_of`).
    """
    prediction = resolve_prediction_error(prediction_rows, band_key_of(predicted_at, holidays))
    expected_departure = predicted_at + timedelta(seconds=prediction.value.mean)
    travel = resolve_travel_time(travel_rows, band_key_of(expected_departure, holidays), planned_travel_sec)
    return ResolvedCandidate(
        candidate=vehicle_candidate(label, predicted_at, prediction.value, travel.value),
        prediction_error=prediction,
        travel_time=travel,
    )


def min_sample_count(chosen: Iterable[ResolvedCandidate]) -> int | None:
    """고른 차량들의 입력(예측 오차, 차내 시간)이 기댄 표본 수 중 가장 작은 값 (#86).

    추천의 성공확률은 TRANSIT 구간의 이 두 분포로만 계산되므로(도보는 출발 시각만 당긴다) "이 확률 뒤에
    실측이 얼마나 있나"는 가장 얇은 입력이 정한다. 기본값으로 내려간 입력은 0이고, 그 밖에는
    `MIN_CALIBRATION_SAMPLES` 이상이다. 고른 차량이 없으면(TRANSIT 구간이 없는 경로) `None`.
    """
    counts = [
        resolved.sample_count
        for candidate in chosen
        for resolved in (candidate.prediction_error, candidate.travel_time)
    ]
    return min(counts) if counts else None


def vehicle_candidate(
    label: str, predicted_at: datetime, prediction_error: Normal, travel: Normal
) -> VehicleCandidate:
    """`V_board ~ N(predicted_at + bias, σ_pred²)`, `V_alight = V_board + D` (ALGORITHM 2.2)."""
    board = TimeNormal(
        mean_at=predicted_at + timedelta(seconds=prediction_error.mean),
        stddev_sec=prediction_error.stddev,
    )
    return VehicleCandidate(label=label, board=board, alight=board.shifted_by(travel))
