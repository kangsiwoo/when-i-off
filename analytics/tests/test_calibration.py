"""calibrate의 순수 통계 (ALGORITHM 5절, 합성 데이터)."""

from __future__ import annotations

import statistics
from datetime import date, datetime, time, timedelta

import pytest

from conftest import kst
from whenioff_analytics import defaults
from whenioff_analytics.daytype import UTC, DayType
from whenioff_analytics.model.calibration import (
    LAST_BAND_END,
    PREDICTION_STDDEV_FLOOR_SEC,
    TRAVEL_TIME_STDDEV_FLOOR_SEC,
    WALKING_STDDEV_FLOOR_MPS,
    AttemptSample,
    BandKey,
    WalkSample,
    band_key_of,
    prediction_calibrations,
    round_half_away,
    stddev_or_default,
    time_band_of,
    travel_time_calibrations,
    walking_profiles,
)

LINE, BOARD, ALIGHT = 7, 100, 200
NO_HOLIDAYS: frozenset[date] = frozenset()


def walk(leg: int, started: datetime, speed: float, user: int = 1, trip: int = 1) -> WalkSample:
    return WalkSample(
        user_id=user, route_leg_id=leg, commute_trip_id=trip, started_at=started, avg_speed_mps=speed
    )


def attempt(
    result: str,
    predicted: datetime | None,
    departed: datetime | None,
    alighted: datetime | None = None,
    attempt_id: int = 1,
    board: int = BOARD,
) -> AttemptSample:
    return AttemptSample(
        attempt_id=attempt_id,
        transit_line_id=LINE,
        board_stop_id=board,
        alight_stop_id=ALIGHT,
        result=result,
        predicted_at=predicted,
        actual_departure_at=departed,
        alighted_at=alighted,
    )


# ---------------------------------------------------------------------------
# 공통 규칙
# ---------------------------------------------------------------------------


def test_round_half_away_is_symmetric() -> None:
    assert [round_half_away(v) for v in (12.5, -12.5, 0.4, -0.6, 3.0)] == [13, -13, 0, -1, 3]


def test_stddev_uses_default_for_a_single_sample_and_sample_stddev_otherwise() -> None:
    assert stddev_or_default([1.3], default=0.15, floor=0.05) == 0.15
    assert stddev_or_default([1.0, 1.4], default=0.15, floor=0.05) == pytest.approx(
        statistics.stdev([1.0, 1.4])
    )
    # 같은 값 둘이면 σ = 0이 되지만 하한이 받친다.
    assert stddev_or_default([1.2, 1.2], default=0.15, floor=0.05) == 0.05
    with pytest.raises(ValueError, match="no samples"):
        stddev_or_default([], default=0.15, floor=0.05)


def test_time_band_is_the_kst_half_hour_and_the_last_band_ends_at_time_max() -> None:
    assert time_band_of(kst("2026-09-22 08:00")) == (time(8, 0), time(8, 30))
    assert time_band_of(kst("2026-09-22 08:29:59")) == (time(8, 0), time(8, 30))
    assert time_band_of(kst("2026-09-22 08:30")) == (time(8, 30), time(9, 0))
    assert time_band_of(kst("2026-09-22 23:45")) == (time(23, 30), LAST_BAND_END)
    assert time(23, 59, 59, 999999) == LAST_BAND_END
    assert time_band_of(kst("2026-09-23 00:10")) == (time(0, 0), time(0, 30))


def test_band_key_uses_the_kst_date_not_the_utc_date() -> None:
    # 월요일 UTC 23:50 = KST 화요일 08:50. UTC 날짜로 보면 월요일 23:30 밴드가 된다.
    moment = datetime(2026, 9, 21, 23, 50, tzinfo=UTC)
    assert band_key_of(moment, NO_HOLIDAYS) == BandKey(DayType.WEEKDAY, time(8, 30), time(9, 0))
    # 금요일 UTC 23:50 = KST 토요일 08:50
    assert band_key_of(datetime(2026, 9, 18, 23, 50, tzinfo=UTC), NO_HOLIDAYS).day_type == DayType.SATURDAY


def test_band_key_treats_a_weekday_holiday_as_sunday_holiday() -> None:
    # 2026-10-09(금) 한글날 — 기본 공휴일 목록을 쓴다.
    assert band_key_of(kst("2026-10-09 08:10")).day_type == DayType.SUNDAY_HOLIDAY
    assert band_key_of(kst("2026-10-08 08:10")).day_type == DayType.WEEKDAY


# ---------------------------------------------------------------------------
# user_walking_profile
# ---------------------------------------------------------------------------


def test_walking_excludes_outliers_before_aggregating() -> None:
    t0 = kst("2026-09-01 08:00")
    samples = [
        walk(10, t0, 1.0),
        walk(10, t0 + timedelta(days=1), 1.4),
        walk(10, t0 + timedelta(days=2), 0.29),  # 멈춰 섰다
        walk(10, t0 + timedelta(days=3), 3.01),  # 뛰었거나 탔다
        walk(10, t0 + timedelta(days=4), 0.3),  # 경계는 포함
    ]
    profiles, skipped = walking_profiles(samples)
    leg = next(p for p in profiles if p.route_leg_id == 10)
    assert leg.sample_count == 3
    assert leg.avg_speed_mps == pytest.approx((1.0 + 1.4 + 0.3) / 3)
    assert skipped == {"too_slow": 1, "too_fast": 1}


def test_walking_window_keeps_the_latest_30_by_started_at() -> None:
    t0 = kst("2026-08-01 08:00")
    # 오래된 10개는 0.5, 최근 30개는 1.5. 입력 순서를 섞어도 started_at으로 고른다.
    old = [walk(10, t0 + timedelta(days=i), 0.5, trip=i) for i in range(10)]
    new = [walk(10, t0 + timedelta(days=10 + i), 1.5, trip=10 + i) for i in range(30)]
    profiles, _ = walking_profiles(list(reversed(new)) + old)
    leg = next(p for p in profiles if p.route_leg_id == 10)
    assert leg.sample_count == 30
    assert leg.avg_speed_mps == pytest.approx(1.5)
    # 모두 같은 값이라 σ는 하한이다.
    assert leg.stddev_speed_mps == WALKING_STDDEV_FLOOR_MPS


def test_walking_global_row_takes_the_latest_30_across_legs_per_user() -> None:
    t0 = kst("2026-08-01 08:00")
    # 구간 10: 오래된 20개(1.0), 구간 11: 최근 20개(1.4). 전역은 최근 30 = 구간 11 전부 + 구간 10 최근 10.
    leg10 = [walk(10, t0 + timedelta(days=i), 1.0, trip=i) for i in range(20)]
    leg11 = [walk(11, t0 + timedelta(days=20 + i), 1.4, trip=20 + i) for i in range(20)]
    other_user = [walk(12, t0, 0.9, user=2, trip=99)]
    profiles, _ = walking_profiles(leg10 + leg11 + other_user)

    by_key = {(p.user_id, p.route_leg_id): p for p in profiles}
    assert set(by_key) == {(1, 10), (1, 11), (1, None), (2, 12), (2, None)}
    assert by_key[(1, 10)].sample_count == 20
    assert by_key[(1, 11)].sample_count == 20
    glob = by_key[(1, None)]
    assert glob.sample_count == 30
    assert glob.avg_speed_mps == pytest.approx((10 * 1.0 + 20 * 1.4) / 30)
    assert glob.stddev_speed_mps == pytest.approx(statistics.stdev([1.0] * 10 + [1.4] * 20))


def test_walking_single_sample_uses_the_cold_start_stddev() -> None:
    profiles, _ = walking_profiles([walk(10, kst("2026-09-01 08:00"), 1.1)])
    assert [p.sample_count for p in profiles] == [1, 1]
    assert all(p.stddev_speed_mps == defaults.WALKING_SPEED.stddev for p in profiles)


def test_walking_user_with_only_outliers_has_no_rows() -> None:
    profiles, skipped = walking_profiles([walk(10, kst("2026-09-01 08:00"), 5.0)])
    assert profiles == []
    assert skipped == {"too_fast": 1}


# ---------------------------------------------------------------------------
# transit_prediction_calibration
# ---------------------------------------------------------------------------


def test_prediction_error_counts_missed_and_caught_attempts() -> None:
    predicted = kst("2026-09-22 08:03")
    rows, skipped = prediction_calibrations(
        [
            # 놓친 차: 예측 08:03, 실제 08:04 (+60)
            attempt("MISSED", predicted, predicted + timedelta(seconds=60), attempt_id=1),
            # 탄 다음 차: 예측 08:13, 실제 08:13:30 (+30). 같은 08:00 밴드.
            attempt(
                "CAUGHT",
                kst("2026-09-22 08:13"),
                kst("2026-09-22 08:13:30"),
                kst("2026-09-22 08:40"),
                attempt_id=2,
            ),
            attempt("MISSED", predicted, None, attempt_id=3),
            attempt("UNKNOWN", None, predicted, attempt_id=4),
        ],
        NO_HOLIDAYS,
    )
    assert len(rows) == 1
    row = rows[0]
    assert (row.transit_line_id, row.stop_id) == (LINE, BOARD)
    assert row.band == BandKey(DayType.WEEKDAY, time(8, 0), time(8, 30))
    assert row.sample_count == 2
    assert row.bias_sec == 45
    assert row.stddev_sec == round_half_away(statistics.stdev([60.0, 30.0]))  # 21.2 → 21
    assert skipped == {"missing_departure": 1, "missing_predicted": 1}


def test_prediction_groups_by_board_stop_day_type_and_band_of_the_predicted_time() -> None:
    rows, _ = prediction_calibrations(
        [
            attempt("CAUGHT", kst("2026-09-22 08:29"), kst("2026-09-22 08:31"), attempt_id=1),  # 08:00 밴드
            attempt("CAUGHT", kst("2026-09-22 08:30"), kst("2026-09-22 08:30"), attempt_id=2),  # 08:30 밴드
            attempt("CAUGHT", kst("2026-09-26 08:10"), kst("2026-09-26 08:10"), attempt_id=3),  # 토요일
            attempt("CAUGHT", kst("2026-09-22 08:10"), kst("2026-09-22 08:10"), attempt_id=4, board=101),
        ],
        NO_HOLIDAYS,
    )
    keys = {(r.stop_id, r.band.day_type, r.band.time_band_start) for r in rows}
    assert keys == {
        (BOARD, DayType.WEEKDAY, time(8, 0)),
        (BOARD, DayType.WEEKDAY, time(8, 30)),
        (BOARD, DayType.SATURDAY, time(8, 0)),
        (101, DayType.WEEKDAY, time(8, 0)),
    }
    # 실제 출발이 밴드 밖(08:31)이어도 예측 시각의 밴드로 묶인다.
    first = next(
        r
        for r in rows
        if r.band.time_band_start == time(8, 0) and r.band.day_type == DayType.WEEKDAY and r.stop_id == BOARD
    )
    assert first.bias_sec == 120


def test_prediction_single_sample_uses_the_cold_start_stddev_and_tiny_spread_hits_the_floor() -> None:
    single, _ = prediction_calibrations(
        [attempt("MISSED", kst("2026-09-22 08:03"), kst("2026-09-22 08:02"))], NO_HOLIDAYS
    )
    assert single[0].stddev_sec == defaults.PREDICTION_STDDEV_SEC
    assert single[0].bias_sec == -60

    tight, _ = prediction_calibrations(
        [
            attempt("CAUGHT", kst("2026-09-22 08:03"), kst("2026-09-22 08:03:10"), attempt_id=1),
            attempt("CAUGHT", kst("2026-09-23 08:03"), kst("2026-09-23 08:03:10"), attempt_id=2),
        ],
        NO_HOLIDAYS,
    )
    assert tight[0].sample_count == 2
    assert tight[0].stddev_sec == PREDICTION_STDDEV_FLOOR_SEC


def test_prediction_bias_rounds_half_away_from_zero() -> None:
    rows, _ = prediction_calibrations(
        [
            attempt("CAUGHT", kst("2026-09-22 08:03"), kst("2026-09-22 08:02:48"), attempt_id=1),  # -12
            attempt("CAUGHT", kst("2026-09-23 08:03"), kst("2026-09-23 08:02:47"), attempt_id=2),  # -13
        ],
        NO_HOLIDAYS,
    )
    assert rows[0].bias_sec == -13  # -12.5


# ---------------------------------------------------------------------------
# transit_travel_time_calibration
# ---------------------------------------------------------------------------


def test_travel_time_uses_only_caught_attempts() -> None:
    departed = kst("2026-09-22 08:14")
    rows, skipped = travel_time_calibrations(
        [
            attempt("MISSED", kst("2026-09-22 08:03"), kst("2026-09-22 08:04"), attempt_id=1),
            attempt("UNKNOWN", departed, departed, departed + timedelta(minutes=5), attempt_id=2),
            attempt("CAUGHT", departed, departed, departed + timedelta(minutes=20), attempt_id=3),
            attempt("CAUGHT", departed, departed, None, attempt_id=4),
            attempt("CAUGHT", departed, None, departed, attempt_id=5),
            attempt("CAUGHT", departed, departed, departed, attempt_id=6),
            attempt("CAUGHT", departed, departed, departed - timedelta(seconds=5), attempt_id=7),
        ],
        NO_HOLIDAYS,
    )
    assert len(rows) == 1
    row = rows[0]
    assert (row.transit_line_id, row.board_stop_id, row.alight_stop_id) == (LINE, BOARD, ALIGHT)
    assert row.band == BandKey(DayType.WEEKDAY, time(8, 0), time(8, 30))
    assert row.sample_count == 1
    assert row.mean_sec == 1200
    # 샘플 1개: σ = 평균의 15%
    assert row.stddev_sec == 180
    assert skipped == {"missing_alighted": 1, "missing_departure": 1, "non_positive_duration": 2}


def test_travel_time_groups_by_the_kst_band_of_the_actual_departure() -> None:
    # 예측은 KST 08:25(08:00 밴드)였지만 실제 출발 08:35 → 08:30 밴드. UTC로는 전날 23:35다.
    departed = datetime(2026, 9, 21, 23, 35, tzinfo=UTC)
    rows, _ = travel_time_calibrations(
        [
            attempt(
                "CAUGHT", kst("2026-09-22 08:25"), departed, departed + timedelta(seconds=1000), attempt_id=1
            ),
            attempt(
                "CAUGHT", kst("2026-09-22 08:25"), departed, departed + timedelta(seconds=1101), attempt_id=2
            ),
        ],
        NO_HOLIDAYS,
    )
    assert len(rows) == 1
    assert rows[0].band == BandKey(DayType.WEEKDAY, time(8, 30), time(9, 0))
    assert rows[0].mean_sec == 1051  # 1050.5 → 1051
    assert rows[0].stddev_sec == round_half_away(statistics.stdev([1000.0, 1101.0]))


def test_travel_time_small_spread_hits_the_floor() -> None:
    d1, d2 = kst("2026-09-22 08:14"), kst("2026-09-23 08:14")
    rows, _ = travel_time_calibrations(
        [
            attempt("CAUGHT", d1, d1, d1 + timedelta(seconds=600), attempt_id=1),
            attempt("CAUGHT", d2, d2, d2 + timedelta(seconds=602), attempt_id=2),
        ],
        NO_HOLIDAYS,
    )
    assert rows[0].stddev_sec == TRAVEL_TIME_STDDEV_FLOOR_SEC
