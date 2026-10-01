"""recommend가 보정 테이블에서 입력을 고르는 규칙 (#46, 합성 데이터).

조회 순서 · 5회 경계 · 그룹 합치기(pool) 수학 · 후보 차량마다의 밴드 선택 · 샘플이 쌓이면 buffer가
줄어드는 것(#6 완료 기준)을 잡는다.
"""

from __future__ import annotations

import random
import statistics
from datetime import date, datetime, time, timedelta

import pytest

from conftest import kst
from whenioff_analytics import defaults
from whenioff_analytics.daytype import UTC, DayType
from whenioff_analytics.model.calibration import (
    LAST_BAND_END,
    PREDICTION_STDDEV_FLOOR_SEC,
    AttemptSample,
    BandKey,
    PredictionCalibration,
    TravelTimeCalibration,
    WalkingProfile,
    WalkSample,
    band_key_of,
    prediction_calibrations,
    travel_time_calibrations,
    walking_profiles,
)
from whenioff_analytics.model.distributions import Normal, TimeNormal, walk_time
from whenioff_analytics.model.lookup import (
    MIN_CALIBRATION_SAMPLES,
    Provenance,
    SampleStats,
    pool_stats,
    resolve_candidate,
    resolve_prediction_error,
    resolve_travel_time,
    resolve_walking_speed,
)
from whenioff_analytics.model.recommend import Leg, TransitLeg, VehicleCandidate, WalkLeg, recommend_departure

LINE, BOARD, ALIGHT = 7, 100, 200
LEG = 11
NO_HOLIDAYS: frozenset[date] = frozenset()
PLANNED = 1200.0
WEEKDAY_0800 = BandKey(DayType.WEEKDAY, time(8, 0), time(8, 30))
WEEKDAY_0830 = BandKey(DayType.WEEKDAY, time(8, 30), time(9, 0))
SATURDAY_0800 = BandKey(DayType.SATURDAY, time(8, 0), time(8, 30))


def test_threshold_is_five() -> None:
    assert MIN_CALIBRATION_SAMPLES == 5


def profile(leg: int | None, n: int, mean: float = 1.4, sd: float = 0.1) -> WalkingProfile:
    return WalkingProfile(
        user_id=1, route_leg_id=leg, avg_speed_mps=mean, stddev_speed_mps=sd, sample_count=n
    )


def prediction(band: BandKey, n: int, bias: int, sd: int) -> PredictionCalibration:
    return PredictionCalibration(
        transit_line_id=LINE, stop_id=BOARD, band=band, bias_sec=bias, stddev_sec=sd, sample_count=n
    )


def travel(band: BandKey, n: int, mean: int, sd: int) -> TravelTimeCalibration:
    return TravelTimeCalibration(
        transit_line_id=LINE,
        board_stop_id=BOARD,
        alight_stop_id=ALIGHT,
        band=band,
        mean_sec=mean,
        stddev_sec=sd,
        sample_count=n,
    )


# ---------------------------------------------------------------------------
# 도보 속도: 구간 행 → 전역 행 → 기본값
# ---------------------------------------------------------------------------


def test_walking_uses_the_leg_row_from_five_samples() -> None:
    resolved = resolve_walking_speed([profile(LEG, 5, 1.4, 0.1), profile(None, 30, 1.0, 0.2)], LEG)
    assert resolved.provenance is Provenance.CALIBRATED
    assert resolved.value == Normal(1.4, 0.1)
    assert resolved.sample_count == 5


def test_walking_inherits_the_global_row_below_five_leg_samples() -> None:
    resolved = resolve_walking_speed([profile(LEG, 4, 1.4, 0.1), profile(None, 5, 1.0, 0.2)], LEG)
    assert resolved.provenance is Provenance.INHERITED
    assert resolved.value == Normal(1.0, 0.2)
    assert resolved.sample_count == 5


def test_walking_ignores_other_legs_rows() -> None:
    resolved = resolve_walking_speed([profile(LEG + 1, 30, 1.4, 0.1), profile(None, 6, 1.0, 0.2)], LEG)
    assert resolved.provenance is Provenance.INHERITED
    assert resolved.value.mean == 1.0


def test_walking_falls_back_to_default_when_both_rows_are_thin() -> None:
    assert resolve_walking_speed([profile(LEG, 4), profile(None, 4)], LEG).provenance is Provenance.DEFAULT
    resolved = resolve_walking_speed([], LEG)
    assert resolved.provenance is Provenance.DEFAULT
    assert resolved.value == defaults.WALKING_SPEED
    assert resolved.sample_count == 0


# ---------------------------------------------------------------------------
# 그룹 합치기
# ---------------------------------------------------------------------------


def test_pooled_stats_equal_the_stats_of_all_samples_together() -> None:
    rng = random.Random(46)
    groups = [[rng.gauss(mu, sd) for _ in range(n)] for mu, sd, n in ((10, 5, 3), (-20, 30, 7), (40, 2, 2))]
    groups.append([55.0])  # n = 1 그룹: 저장된 σ는 콜드스타트 값이라 결과에 섞이면 안 된다
    stats = [
        SampleStats(len(g), statistics.fmean(g), statistics.stdev(g) if len(g) > 1 else 90.0) for g in groups
    ]

    pooled = pool_stats(stats)
    everything = [x for g in groups for x in g]

    assert pooled is not None
    assert pooled.count == len(everything) == 13
    assert pooled.mean == pytest.approx(statistics.fmean(everything))
    assert pooled.stddev == pytest.approx(statistics.stdev(everything))
    # σ의 (가중) 평균과는 다르다 — 그룹 사이 평균 차이가 퍼짐에 들어가기 때문이다.
    weighted_sd = sum(s.count * s.stddev for s in stats) / pooled.count
    assert abs(pooled.stddev - weighted_sd) > 1.0


def test_pooling_edge_cases() -> None:
    assert pool_stats([]) is None
    assert pool_stats([SampleStats(0, 1.0, 1.0)]) is None
    assert pool_stats([SampleStats(1, 3.0, 90.0)]) == SampleStats(1, 3.0, 90.0)
    # 두 그룹이 평균은 다르고 각자 퍼짐이 없으면 σ는 그룹 사이 차이에서만 나온다: 샘플 [0, 0, 10, 10]
    pooled = pool_stats([SampleStats(2, 0.0, 0.0), SampleStats(2, 10.0, 0.0)])
    assert pooled is not None
    assert pooled.stddev == pytest.approx(statistics.stdev([0, 0, 10, 10]))


# ---------------------------------------------------------------------------
# 예측 오차: 정확한 키 → 노선·정류장 합침 → (0, 90)
# ---------------------------------------------------------------------------


def test_prediction_uses_the_exact_band_from_five_samples() -> None:
    rows = [prediction(WEEKDAY_0800, 5, 30, 40), prediction(WEEKDAY_0830, 50, -60, 20)]
    resolved = resolve_prediction_error(rows, WEEKDAY_0800)
    assert resolved.provenance is Provenance.CALIBRATED
    assert resolved.value == Normal(30.0, 40.0)
    assert resolved.sample_count == 5


def test_prediction_inherits_the_pooled_line_stop_value_below_five() -> None:
    rows = [
        prediction(WEEKDAY_0800, 4, 30, 40),
        prediction(WEEKDAY_0830, 3, -10, 20),
        prediction(SATURDAY_0800, 1, 100, 90),
    ]
    resolved = resolve_prediction_error(rows, WEEKDAY_0800)
    expected = pool_stats([SampleStats(4, 30, 40), SampleStats(3, -10, 20), SampleStats(1, 100, 90)])

    assert resolved.provenance is Provenance.INHERITED
    assert expected is not None
    assert resolved.value == Normal(expected.mean, expected.stddev)
    assert resolved.sample_count == 8


def test_prediction_inherits_when_the_exact_band_has_no_row() -> None:
    resolved = resolve_prediction_error([prediction(WEEKDAY_0830, 5, 12, 30)], WEEKDAY_0800)
    assert resolved.provenance is Provenance.INHERITED
    assert resolved.value == Normal(12.0, 30.0)


def test_prediction_falls_back_to_default_when_the_pool_is_below_five() -> None:
    rows = [prediction(WEEKDAY_0800, 2, 30, 40), prediction(WEEKDAY_0830, 2, -10, 20)]
    resolved = resolve_prediction_error(rows, WEEKDAY_0800)
    assert resolved.provenance is Provenance.DEFAULT
    assert resolved.value == Normal(defaults.PREDICTION_BIAS_SEC, defaults.PREDICTION_STDDEV_SEC)
    assert resolve_prediction_error([], WEEKDAY_0800).provenance is Provenance.DEFAULT


def test_pooled_stddev_does_not_fall_below_the_floor() -> None:
    # 같은 평균·하한 σ의 그룹들을 합치면 (n − 1) 가중 때문에 하한보다 살짝 작아진다.
    rows = [prediction(WEEKDAY_0830, 2, 0, 15), prediction(SATURDAY_0800, 3, 0, 15)]
    resolved = resolve_prediction_error(rows, WEEKDAY_0800)
    assert resolved.provenance is Provenance.INHERITED
    assert resolved.value.stddev == PREDICTION_STDDEV_FLOOR_SEC


# ---------------------------------------------------------------------------
# 차내 시간: 정확한 키 → 역 쌍 합침 → planned, 15%
# ---------------------------------------------------------------------------


def test_travel_time_levels() -> None:
    exact = resolve_travel_time([travel(WEEKDAY_0800, 5, 1100, 30)], WEEKDAY_0800, PLANNED)
    assert (exact.provenance, exact.value) == (Provenance.CALIBRATED, Normal(1100.0, 30.0))

    rows = [travel(WEEKDAY_0800, 4, 1100, 30), travel(WEEKDAY_0830, 1, 1150, 180)]
    inherited = resolve_travel_time(rows, WEEKDAY_0800, PLANNED)
    expected = pool_stats([SampleStats(4, 1100, 30), SampleStats(1, 1150, 180)])
    assert expected is not None
    assert inherited.provenance is Provenance.INHERITED
    assert inherited.value == Normal(expected.mean, expected.stddev)
    assert inherited.sample_count == 5

    default = resolve_travel_time([travel(WEEKDAY_0800, 4, 1100, 30)], WEEKDAY_0800, PLANNED)
    assert default.provenance is Provenance.DEFAULT
    assert default.value == Normal(PLANNED, PLANNED * defaults.TRAVEL_TIME_CV)


# ---------------------------------------------------------------------------
# 후보 차량마다의 밴드
# ---------------------------------------------------------------------------


def test_each_candidate_uses_the_band_of_its_own_time() -> None:
    rows = [prediction(WEEKDAY_0800, 6, 10, 20), prediction(WEEKDAY_0830, 6, 50, 60)]
    early = resolve_candidate("a", kst("2026-09-22 08:29:59"), rows, [], PLANNED, NO_HOLIDAYS)
    late = resolve_candidate("b", kst("2026-09-22 08:30"), rows, [], PLANNED, NO_HOLIDAYS)

    assert early.prediction_error.value == Normal(10.0, 20.0)
    assert late.prediction_error.value == Normal(50.0, 60.0)
    assert early.candidate.board.mean_at == kst("2026-09-22 08:30:09")
    assert late.candidate.board.stddev_sec == 60.0


def test_band_and_day_type_follow_the_kst_date_across_midnight() -> None:
    """금요일 23:50 KST와 토요일 00:10 KST(= UTC 금요일 15:10)는 day_type도 밴드도 다르다."""
    friday_late = BandKey(DayType.WEEKDAY, time(23, 30), LAST_BAND_END)
    saturday_early = BandKey(DayType.SATURDAY, time(0, 0), time(0, 30))
    rows = [prediction(friday_late, 5, -30, 20), prediction(saturday_early, 5, 40, 25)]

    before = datetime(2026, 10, 16, 14, 50, tzinfo=UTC)  # 2026-10-16(금) 23:50 KST
    after = datetime(2026, 10, 16, 15, 10, tzinfo=UTC)  # 2026-10-17(토) 00:10 KST
    assert band_key_of(after, NO_HOLIDAYS) == saturday_early

    assert resolve_candidate("x", before, rows, [], PLANNED, NO_HOLIDAYS).prediction_error.value.mean == -30
    assert resolve_candidate("y", after, rows, [], PLANNED, NO_HOLIDAYS).prediction_error.value.mean == 40


def test_travel_band_is_the_expected_departure_not_the_prediction() -> None:
    """calibrate는 차내 시간을 실제 출발 시각으로 묶는다. 예측 08:29:30 + bias 60초 = 08:30:30 출발."""
    rows = [prediction(WEEKDAY_0800, 5, 60, 20)]
    travel_rows = [travel(WEEKDAY_0800, 5, 1000, 20), travel(WEEKDAY_0830, 5, 1500, 20)]
    resolved = resolve_candidate("z", kst("2026-09-22 08:29:30"), rows, travel_rows, PLANNED, NO_HOLIDAYS)
    assert resolved.travel_time.value.mean == 1500


def test_cold_start_candidate_is_numerically_the_v1_candidate() -> None:
    at = kst("2026-09-22 08:14")
    resolved = resolve_candidate("v", at, [], [], PLANNED, NO_HOLIDAYS)
    v1_board = TimeNormal(mean_at=at, stddev_sec=defaults.PREDICTION_STDDEV_SEC)
    v1 = VehicleCandidate(
        label="v",
        board=v1_board,
        alight=v1_board.shifted_by(Normal(PLANNED, PLANNED * defaults.TRAVEL_TIME_CV)),
    )
    assert resolved.candidate == v1
    assert resolved.prediction_error.provenance is Provenance.DEFAULT
    assert resolved.travel_time.provenance is Provenance.DEFAULT


# ---------------------------------------------------------------------------
# #6 완료 기준: 샘플이 쌓이면 buffer가 줄어든다
# ---------------------------------------------------------------------------


def _recommend_after(n: int) -> tuple[int, Provenance]:
    """평일 아침 통근 n번의 (σ가 좁은) 실측을 calibrate의 순수 함수로 집계하고, 그 행으로 추천한다.

    경로: 집 → 600m 도보 → 승차(시간표 08:00·08:15·08:30) → 1200s 계획 → 300m 도보 → 09:00 목표.
    """
    rng = random.Random(6)
    walks: list[WalkSample] = []
    attempts: list[AttemptSample] = []
    for day in range(n):
        start = kst("2026-08-03 08:10") + timedelta(days=day)
        for leg in (1, 3):
            walks.append(WalkSample(1, leg, day, start, rng.gauss(1.35, 0.03)))
        predicted = start + timedelta(minutes=20)
        departed = predicted + timedelta(seconds=rng.gauss(20, 10))
        alighted = departed + timedelta(seconds=rng.gauss(1150, 15))
        attempts.append(AttemptSample(day, LINE, BOARD, ALIGHT, "CAUGHT", predicted, departed, alighted))
    # 날짜는 평일·주말이 섞이므로(day_type이 다른 행) 상속 경로도 함께 지난다.
    profiles, _ = walking_profiles(walks)
    predictions, _ = prediction_calibrations(attempts, NO_HOLIDAYS)
    travel_rows, _ = travel_time_calibrations(attempts, NO_HOLIDAYS)

    target = kst("2026-09-22 09:00")
    candidates = [
        resolve_candidate(at, kst(f"2026-09-22 {at}"), predictions, travel_rows, PLANNED, NO_HOLIDAYS)
        for at in ("08:00", "08:15", "08:30")
    ]
    first = resolve_walking_speed(profiles, 1)
    legs: tuple[Leg, ...] = (
        WalkLeg(1, walk_time(600.0, first.value)),
        TransitLeg(2, tuple(c.candidate for c in candidates)),
        WalkLeg(3, walk_time(300.0, resolve_walking_speed(profiles, 3).value)),
    )
    result = recommend_departure(legs, target, 0.95)
    chosen = next(c for c in candidates if c.candidate == result.chosen[0].candidate)
    return result.buffer_seconds, chosen.prediction_error.provenance


def test_buffer_shrinks_as_tight_samples_accumulate() -> None:
    cold, cold_source = _recommend_after(0)
    # 하루에 도보 실측이 둘(구간 1·3)이라 전역 행은 2일이면 4개 — 모든 입력이 아직 5개 미만이다.
    thin, thin_source = _recommend_after(2)
    enough, _ = _recommend_after(MIN_CALIBRATION_SAMPLES + 5)
    many, many_source = _recommend_after(40)

    assert cold_source is thin_source is Provenance.DEFAULT
    assert thin == cold  # 5개 미만으로는 아무것도 바뀌지 않는다
    assert many_source is Provenance.CALIBRATED
    assert enough < cold
    assert many < cold
    buffers = [_recommend_after(n)[0] for n in range(0, 12)]
    assert buffers == sorted(buffers, reverse=True)  # 샘플이 늘 때 한 번도 커지지 않는다
    # 기본값(σ 90초 + 15%) 대비 좁은 실측(σ 10~15초)이면 여유가 크게 준다.
    assert many < cold / 2
