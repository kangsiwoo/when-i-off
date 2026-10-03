"""추천에 싣는 표본 수 (#86): 고른 차량들의 입력 표본 수 최솟값과, 그 값까지 비교하는 적재 멱등성."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime, time
from typing import Any

from conftest import kst
from whenioff_analytics import defaults
from whenioff_analytics.daytype import DayType
from whenioff_analytics.io.recommendations import RecommendationRow, SaveOutcome, save_recommendation
from whenioff_analytics.io.routes import CommuteRoute
from whenioff_analytics.model.calibration import BandKey, PredictionCalibration, TravelTimeCalibration
from whenioff_analytics.model.distributions import Normal
from whenioff_analytics.model.lookup import (
    MIN_CALIBRATION_SAMPLES,
    Provenance,
    ResolvedCandidate,
    min_sample_count,
    resolve_candidate,
)
from whenioff_analytics.model.recommend import Leg, TransitLeg, WalkLeg, recommend_departure
from whenioff_analytics.service import LegInputs, RecommendationResult, recommendation_row

LINE, BOARD, ALIGHT = 7, 100, 200
PLANNED = 1200.0
WEEKDAY_0800 = BandKey(DayType.WEEKDAY, time(8, 0), time(8, 30))
WEEKDAY_0830 = BandKey(DayType.WEEKDAY, time(8, 30), time(9, 0))


def candidate(at: str, prediction_n: int, travel_n: int) -> ResolvedCandidate:
    """`at`(KST 08:xx) 차. 예측 오차 행은 08:00 밴드, 차내 시간 행은 08:30 밴드에 n개씩 둔다."""
    prediction_rows = [PredictionCalibration(LINE, BOARD, WEEKDAY_0800, 20, 30, prediction_n)]
    travel_rows = [TravelTimeCalibration(LINE, BOARD, ALIGHT, WEEKDAY_0830, 1150, 40, travel_n)]
    return resolve_candidate(at, kst(f"2026-09-22 {at}"), prediction_rows, travel_rows, PLANNED, frozenset())


def test_min_sample_count_is_the_thinnest_input_of_the_chosen_vehicles() -> None:
    both = candidate("08:15", prediction_n=12, travel_n=8)
    assert both.prediction_error.provenance is Provenance.CALIBRATED
    assert min_sample_count([both]) == 8
    # 다른 구간의 차가 더 얇으면 그 값이다.
    assert min_sample_count([both, candidate("08:15", prediction_n=6, travel_n=30)]) == 6


def test_an_input_that_fell_back_to_defaults_counts_as_zero() -> None:
    thin = candidate("08:15", prediction_n=12, travel_n=MIN_CALIBRATION_SAMPLES - 1)
    assert thin.travel_time.provenance is Provenance.DEFAULT
    assert min_sample_count([thin]) == 0
    assert min_sample_count([candidate("08:15", prediction_n=0, travel_n=0)]) == 0


def test_no_chosen_vehicle_means_unknown_not_zero() -> None:
    assert min_sample_count([]) is None


def _result(legs: tuple[Leg, ...], inputs: tuple[LegInputs, ...]) -> RecommendationResult:
    target = kst("2026-09-22 09:00")
    return RecommendationResult(
        route=CommuteRoute(id=3, user_id=1, name="출근", legs=()),
        target_arrival_at=target,
        probability=0.95,
        legs=legs,
        inputs=inputs,
        recommendation=recommend_departure(legs, target, 0.95),
    )


def test_row_carries_the_count_of_the_vehicles_actually_chosen() -> None:
    # 08:00 차는 표본이 많지만, 역산은 목표에 맞는 가장 늦은 차(08:15)를 고른다. 후보마다 입력이 달라도
    # "고른 차"의 값만 센다 — 고르지 않은 차의 두꺼운 표본이 콜드스타트를 가리면 안 된다.
    early = candidate("08:00", prediction_n=40, travel_n=40)
    late = candidate("08:15", prediction_n=0, travel_n=0)
    walk = WalkLeg(1, Normal(300.0, 30.0))
    transit = TransitLeg(2, (early.candidate, late.candidate))
    result = _result(
        (walk, transit),
        (LegInputs(route_leg_id=1), LegInputs(route_leg_id=2, candidates=(early, late))),
    )
    chosen = result.recommendation.chosen[0].candidate
    assert chosen == late.candidate
    assert result.min_transit_sample_count() == 0

    row = recommendation_row(result)
    assert row.min_transit_sample_count == 0
    assert row.model_version == defaults.MODEL_VERSION


def test_walk_only_route_has_no_count() -> None:
    walk = WalkLeg(1, Normal(300.0, 30.0))
    result = _result((walk,), (LegInputs(route_leg_id=1),))
    assert recommendation_row(result).min_transit_sample_count is None


# ---------------------------------------------------------------------------
# 적재 멱등성
# ---------------------------------------------------------------------------


class FakeCursor:
    def __init__(self, latest: tuple[Any, ...] | None) -> None:
        self.latest = latest
        self.inserted: list[tuple[Any, ...]] = []
        self._next: tuple[Any, ...] | None = None

    def execute(self, sql: str, params: tuple[Any, ...]) -> None:
        if sql.lstrip().startswith("INSERT"):
            self.inserted.append(params)
            self._next = (42,)
        else:
            self._next = self.latest

    def fetchone(self) -> tuple[Any, ...] | None:
        return self._next


class FakeConnection:
    def __init__(self, cursor: FakeCursor) -> None:
        self._cursor = cursor

    @contextmanager
    def cursor(self) -> Iterator[FakeCursor]:
        yield self._cursor


LEAVE = datetime(2026, 9, 21, 22, 54, 17, tzinfo=UTC)


def row(samples: int | None) -> RecommendationRow:
    return RecommendationRow(
        user_id=1,
        commute_route_id=3,
        target_date=kst("2026-09-22 09:00").date(),
        target_arrival_at=kst("2026-09-22 09:00"),
        recommended_leave_home_at=LEAVE,
        catch_probability=0.9,
        buffer_seconds=102,
        model_version="v2",
        min_transit_sample_count=samples,
    )


def save(latest: tuple[Any, ...] | None, new: RecommendationRow) -> tuple[SaveOutcome, FakeCursor]:
    cursor = FakeCursor(latest)
    outcome, _ = save_recommendation(FakeConnection(cursor), new)  # type: ignore[arg-type]
    return outcome, cursor


def test_same_values_and_same_count_write_nothing() -> None:
    outcome, cursor = save((7, LEAVE, 0.9, 102, 8), row(8))
    assert outcome is SaveOutcome.UNCHANGED
    assert cursor.inserted == []


def test_count_change_alone_appends_a_row() -> None:
    # 보정 배치가 돌아 표본 수만 늘었다 — 조회는 최신 행만 보므로 새 행이 있어야 지금 값이 나간다.
    outcome, cursor = save((7, LEAVE, 0.9, 102, 8), row(9))
    assert outcome is SaveOutcome.APPENDED
    assert cursor.inserted[0][-1] == 9


def test_row_from_before_v8_is_superseded_once() -> None:
    outcome, cursor = save((7, LEAVE, 0.9, 102, None), row(0))
    assert outcome is SaveOutcome.APPENDED
    assert cursor.inserted[0][-1] == 0
    # 기록할 값이 없는 경로(TRANSIT 없음)는 NULL끼리 같다.
    outcome, _ = save((7, LEAVE, 0.9, 102, None), row(None))
    assert outcome is SaveOutcome.UNCHANGED


def test_first_row_is_created_with_its_count() -> None:
    outcome, cursor = save(None, row(5))
    assert outcome is SaveOutcome.CREATED
    assert cursor.inserted[0][-1] == 5
    assert cursor.inserted[0][4] == LEAVE
