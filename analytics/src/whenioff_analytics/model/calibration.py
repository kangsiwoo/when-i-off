"""실측에서 세 보정 테이블의 값을 계산한다 (ALGORITHM 5절, DATA_MODEL).

- `user_walking_profile`: `walking_segments`의 도보 속도. 사용자 × 구간, 사용자 전역(구간 NULL)
- `transit_prediction_calibration`: 시도마다(놓친 차 포함) 실제 출발 − 예측 시각
- `transit_travel_time_calibration`: 탄 시도(`CAUGHT`)의 하차 − 실제 출발

모두 순수 함수다. 그룹 키의 `day_type`과 30분 밴드는 KST 기준이며, 판정은 recommend와 같은
`daytype.py`를 쓴다.

**표준편차 규칙** (세 테이블 공통): 샘플이 2개 이상이면 표본표준편차(n − 1로 나눈다), 1개면
정의되지 않으므로 콜드스타트 기본값(`defaults.py`)을 쓴다. 어느 쪽이든 결과는 하한(`*_STDDEV_FLOOR*`)
아래로 내려가지 않는다 — 우연히 같은 값 두 개가 σ = 0을 만들면 분위수가 한 점으로 무너져
추천이 "절대 안 늦는다"고 믿게 되기 때문이다.
"""

from __future__ import annotations

import math
import statistics
from collections import Counter, defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, datetime, time

from whenioff_analytics import defaults
from whenioff_analytics.daytype import DayType, kst_date_of, kst_time_of, resolve_day_type

WALKING_MIN_SPEED_MPS = 0.3
WALKING_MAX_SPEED_MPS = 3.0
"""이 범위 밖의 도보 속도는 이상치로 뺀다 (멈춰 서서 기다렸거나, 뛰거나 탈것을 탄 기록)."""

WALKING_WINDOW = 30
"""도보 속도는 최근 이만큼의 구간 실측만 본다 (`started_at` 기준). 걸음은 계절·몸 상태에 따라 변한다."""

WALKING_STDDEV_FLOOR_MPS = 0.05
PREDICTION_STDDEV_FLOOR_SEC = 15.0
TRAVEL_TIME_STDDEV_FLOOR_SEC = 15.0
"""σ 하한. geofence 기반 시각은 수십 초 단위로 흔들리므로 그보다 좁은 σ는 측정이 보장하지 못한다."""

BAND_MINUTES = 30
LAST_BAND_END = time.max
"""마지막 밴드(23:30–24:00)의 끝. `TIME`은 24:00을 담지 못해 23:59:59.999999로 둔다.

읽는 쪽은 끝값을 비교할 필요 없이 `time_band_of()`로 시작값을 구해 `time_band_start`와 같은지만 보면 된다.
"""


# ---------------------------------------------------------------------------
# 공통
# ---------------------------------------------------------------------------


def round_half_away(value: float) -> int:
    """INT 컬럼용 반올림. 0에서 먼 쪽으로 올린다 (Python `round()`는 짝수 쪽: 12.5 → 12, 13.5 → 14)."""
    return int(math.copysign(math.floor(abs(value) + 0.5), value))


def stddev_or_default(values: list[float], default: float, floor: float) -> float:
    """n ≥ 2면 표본표준편차(ddof=1), n == 1이면 `default`. 결과는 `floor` 이상이다."""
    if not values:
        raise ValueError("no samples")
    sigma = statistics.stdev(values) if len(values) >= 2 else default
    return max(sigma, floor)


def time_band_of(moment: datetime) -> tuple[time, time]:
    """`moment`의 KST 벽시계가 속한 30분 밴드 `[start, end)`. 마지막 밴드의 끝은 `LAST_BAND_END`."""
    wall = kst_time_of(moment)
    start_minute = (wall.hour * 60 + wall.minute) // BAND_MINUTES * BAND_MINUTES
    end_minute = start_minute + BAND_MINUTES
    start = time(start_minute // 60, start_minute % 60)
    end = LAST_BAND_END if end_minute >= 24 * 60 else time(end_minute // 60, end_minute % 60)
    return start, end


@dataclass(frozen=True)
class BandKey:
    day_type: DayType
    time_band_start: time
    time_band_end: time


def band_key_of(moment: datetime, holidays: frozenset[date] | None = None) -> BandKey:
    """`day_type`은 `moment`의 **KST 날짜**로 정한다 (UTC 23:50은 KST로 다음 날 08:50)."""
    start, end = time_band_of(moment)
    return BandKey(resolve_day_type(kst_date_of(moment), holidays), start, end)


# ---------------------------------------------------------------------------
# user_walking_profile
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class WalkSample:
    user_id: int
    route_leg_id: int
    commute_trip_id: int
    started_at: datetime
    avg_speed_mps: float


@dataclass(frozen=True)
class WalkingProfile:
    user_id: int
    route_leg_id: int | None
    """`None`이면 사용자 전역 행."""
    avg_speed_mps: float
    stddev_speed_mps: float
    sample_count: int


def walking_profiles(samples: Iterable[WalkSample]) -> tuple[list[WalkingProfile], Counter[str]]:
    """사용자 × 구간 행과 사용자 전역 행. 이상치를 먼저 빼고, 그다음 최근 `WALKING_WINDOW`개를 고른다.

    전역 행도 구간별 행을 다시 합치지 않고 그 사용자의 모든 구간 실측에서 최근 30개를 따로 고른다.
    평균은 구간 속도들의 단순 평균이다 (긴 구간에 가중하지 않는다 — 행 하나가 "한 번 걸은 것"이다).
    """
    skipped: Counter[str] = Counter()
    per_leg: dict[tuple[int, int | None], list[WalkSample]] = defaultdict(list)
    for sample in samples:
        if sample.avg_speed_mps < WALKING_MIN_SPEED_MPS:
            skipped["too_slow"] += 1
            continue
        if sample.avg_speed_mps > WALKING_MAX_SPEED_MPS:
            skipped["too_fast"] += 1
            continue
        per_leg[(sample.user_id, sample.route_leg_id)].append(sample)
        per_leg[(sample.user_id, None)].append(sample)

    profiles = []
    for (user_id, route_leg_id), group in sorted(per_leg.items(), key=lambda kv: _walk_sort_key(kv[0])):
        # 같은 시각이면 trip·구간 id로 끊어 다시 돌려도 같은 30개가 뽑히게 한다.
        recent = sorted(group, key=lambda s: (s.started_at, s.commute_trip_id, s.route_leg_id))[
            -WALKING_WINDOW:
        ]
        speeds = [s.avg_speed_mps for s in recent]
        profiles.append(
            WalkingProfile(
                user_id=user_id,
                route_leg_id=route_leg_id,
                avg_speed_mps=statistics.fmean(speeds),
                stddev_speed_mps=stddev_or_default(
                    speeds, defaults.WALKING_SPEED.stddev, WALKING_STDDEV_FLOOR_MPS
                ),
                sample_count=len(speeds),
            )
        )
    return profiles, skipped


def _walk_sort_key(key: tuple[int, int | None]) -> tuple[int, int]:
    user_id, route_leg_id = key
    return user_id, -1 if route_leg_id is None else route_leg_id


# ---------------------------------------------------------------------------
# transit_prediction_calibration / transit_travel_time_calibration
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class AttemptSample:
    """`boarding_attempts` 한 행 + 그 구간(`route_legs`)의 노선·정류장."""

    attempt_id: int
    transit_line_id: int
    board_stop_id: int
    alight_stop_id: int
    result: str
    predicted_at: datetime | None
    actual_departure_at: datetime | None
    alighted_at: datetime | None


@dataclass(frozen=True)
class PredictionCalibration:
    transit_line_id: int
    stop_id: int
    band: BandKey
    bias_sec: int
    stddev_sec: int
    sample_count: int


@dataclass(frozen=True)
class TravelTimeCalibration:
    transit_line_id: int
    board_stop_id: int
    alight_stop_id: int
    band: BandKey
    mean_sec: int
    stddev_sec: int
    sample_count: int


def prediction_calibrations(
    attempts: Iterable[AttemptSample], holidays: frozenset[date] | None = None
) -> tuple[list[PredictionCalibration], Counter[str]]:
    """시도마다 `실제 출발 − 예측`(초). 결과(`CAUGHT`/`MISSED`/`UNKNOWN`)는 가리지 않는다 — 놓친 차도
    그 차의 예측이 얼마나 틀렸는지에 대한 한 샘플이다. 그룹은 노선 × 승차 정류장 × 예측 시각의 KST
    `day_type` × 30분 밴드. 그룹의 모든 샘플을 쓴다(윈도우 없음).
    """
    skipped: Counter[str] = Counter()
    groups: dict[tuple[int, int, BandKey], list[float]] = defaultdict(list)
    for a in attempts:
        if a.predicted_at is None:
            skipped["missing_predicted"] += 1
            continue
        if a.actual_departure_at is None:
            skipped["missing_departure"] += 1
            continue
        error = (a.actual_departure_at - a.predicted_at).total_seconds()
        groups[(a.transit_line_id, a.board_stop_id, band_key_of(a.predicted_at, holidays))].append(error)

    rows = [
        PredictionCalibration(
            transit_line_id=line_id,
            stop_id=stop_id,
            band=band,
            bias_sec=round_half_away(statistics.fmean(errors)),
            stddev_sec=round_half_away(
                stddev_or_default(errors, defaults.PREDICTION_STDDEV_SEC, PREDICTION_STDDEV_FLOOR_SEC)
            ),
            sample_count=len(errors),
        )
        for (line_id, stop_id, band), errors in groups.items()
    ]
    rows.sort(key=lambda r: (r.transit_line_id, r.stop_id, _band_sort(r.band)))
    return rows, skipped


def travel_time_calibrations(
    attempts: Iterable[AttemptSample], holidays: frozenset[date] | None = None
) -> tuple[list[TravelTimeCalibration], Counter[str]]:
    """탄 시도(`CAUGHT`)의 `하차 − 실제 출발`(초). 그룹은 노선 × 승차역 × 하차역 × 출발 시각의 KST
    `day_type` × 30분 밴드. 0초 이하(기록 오류)는 뺀다. 그룹의 모든 샘플을 쓴다(윈도우 없음).

    `CAUGHT`가 아닌 시도는 원래 대상이 아니므로 `skipped`에 세지 않는다.
    """
    skipped: Counter[str] = Counter()
    groups: dict[tuple[int, int, int, BandKey], list[float]] = defaultdict(list)
    for a in attempts:
        if a.result != "CAUGHT":
            continue
        if a.actual_departure_at is None:
            skipped["missing_departure"] += 1
            continue
        if a.alighted_at is None:
            skipped["missing_alighted"] += 1
            continue
        duration = (a.alighted_at - a.actual_departure_at).total_seconds()
        if duration <= 0:
            skipped["non_positive_duration"] += 1
            continue
        band = band_key_of(a.actual_departure_at, holidays)
        groups[(a.transit_line_id, a.board_stop_id, a.alight_stop_id, band)].append(duration)

    rows = []
    for (line_id, board_id, alight_id, band), durations in groups.items():
        mean = statistics.fmean(durations)
        sigma = stddev_or_default(durations, defaults.TRAVEL_TIME_CV * mean, TRAVEL_TIME_STDDEV_FLOOR_SEC)
        rows.append(
            TravelTimeCalibration(
                transit_line_id=line_id,
                board_stop_id=board_id,
                alight_stop_id=alight_id,
                band=band,
                mean_sec=round_half_away(mean),
                stddev_sec=round_half_away(sigma),
                sample_count=len(durations),
            )
        )
    rows.sort(key=lambda r: (r.transit_line_id, r.board_stop_id, r.alight_stop_id, _band_sort(r.band)))
    return rows, skipped


_DAY_TYPE_ORDER = {day_type: index for index, day_type in enumerate(DayType)}


def _band_sort(band: BandKey) -> tuple[int, time]:
    return _DAY_TYPE_ORDER[band.day_type], band.time_band_start
