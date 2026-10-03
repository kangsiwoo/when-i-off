"""교차로 주기 행 선택 (#78): 시간대 → 출처 우선순위 → 기본값. DB 대신 가짜 커서를 쓴다."""

from __future__ import annotations

import dataclasses
from datetime import time
from typing import Any, Self, cast

import pytest

from whenioff_analytics import defaults
from whenioff_analytics.daytype import DayType
from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.routes import RouteLeg, SignalCrossing
from whenioff_analytics.io.signals import (
    SOURCE_PRIORITY,
    CycleBand,
    ResolvedCycle,
    SignalSource,
    load_signal_cycles,
    resolve_cycle,
    select_cycle,
)
from whenioff_analytics.model.distributions import SignalCycle
from whenioff_analytics.service import _walk_leg

USER = SignalCycle(cycle_sec=100.0, red_sec=40.0)
PUBLIC = SignalCycle(cycle_sec=140.0, red_sec=100.0)
ASSUMED = SignalCycle(cycle_sec=130.0, red_sec=95.0)


def band(start: time, end: time, cycle: SignalCycle, source: SignalSource) -> CycleBand:
    return CycleBand(time_band_start=start, time_band_end=end, cycle=cycle, source=source)


def test_band_selection_by_time() -> None:
    bands = (
        band(time(7, 0), time(9, 0), PUBLIC, "PUBLIC_API"),
        band(time(9, 0), time(18, 0), ASSUMED, "PUBLIC_API"),
    )
    assert select_cycle(bands, time(8, 30)) == PUBLIC
    # 끝은 포함하지 않고 다음 시간대의 시작은 포함한다.
    assert select_cycle(bands, time(9, 0)) == ASSUMED
    assert select_cycle(bands, time(6, 59)) is None
    assert select_cycle(bands, time(18, 0)) is None
    assert select_cycle((), time(8, 30)) is None


def test_user_observed_beats_public_api_beats_default_assumption() -> None:
    # 시작 시각 순(load_signal_cycles의 정렬)으로 넘어와도 출처가 순서를 이긴다.
    bands = (
        band(time(0, 0), time(23, 59, 59), ASSUMED, "DEFAULT_ASSUMPTION"),
        band(time(7, 0), time(10, 0), PUBLIC, "PUBLIC_API"),
        band(time(8, 0), time(9, 0), USER, "USER_OBSERVED"),
    )
    assert select_cycle(bands, time(8, 30)) == USER
    assert select_cycle(bands, time(7, 30)) == PUBLIC
    assert select_cycle(bands, time(9, 0)) == PUBLIC
    assert select_cycle(bands, time(12, 0)) == ASSUMED
    # 순서를 뒤집어도 같다.
    assert select_cycle(tuple(reversed(bands)), time(8, 30)) == USER
    assert select_cycle(tuple(reversed(bands)), time(7, 30)) == PUBLIC


def test_same_source_overlap_keeps_the_earlier_row() -> None:
    bands = (
        band(time(7, 0), time(10, 0), PUBLIC, "PUBLIC_API"),
        band(time(8, 0), time(9, 0), ASSUMED, "PUBLIC_API"),
    )
    assert select_cycle(bands, time(8, 30)) == PUBLIC


def test_resolve_falls_back_to_default_cycle() -> None:
    bands = (band(time(7, 0), time(9, 0), USER, "USER_OBSERVED"),)
    assert resolve_cycle(bands, time(8, 0)) == ResolvedCycle(USER, "USER_OBSERVED")
    assert resolve_cycle(bands, time(9, 0)) == ResolvedCycle(defaults.DEFAULT_SIGNAL_CYCLE, None)
    assert resolve_cycle((), time(9, 0)) == ResolvedCycle(defaults.DEFAULT_SIGNAL_CYCLE, None)


def test_priority_order_matches_docs() -> None:
    assert SOURCE_PRIORITY == ("USER_OBSERVED", "PUBLIC_API", "DEFAULT_ASSUMPTION")


Row = tuple[int, time, time, int, int, str]


class FakeCursor:
    def __init__(self, rows: list[Row], params: list[tuple[Any, ...]]) -> None:
        self._rows = rows
        self._params = params

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def execute(self, sql: str, params: tuple[Any, ...]) -> None:
        self._params.append(params)

    def fetchall(self) -> list[Row]:
        return self._rows


class FakeConnection:
    def __init__(self, rows: list[Row]) -> None:
        self.rows = rows
        self.params: list[tuple[Any, ...]] = []

    def cursor(self) -> FakeCursor:
        return FakeCursor(self.rows, self.params)


def test_load_keeps_every_source_grouped_by_signal() -> None:
    fake = FakeConnection(
        [
            (1, time(0, 0), time(23, 59, 59), 130, 95, "DEFAULT_ASSUMPTION"),
            (1, time(8, 0), time(9, 0), 100, 40, "USER_OBSERVED"),
            (2, time(7, 0), time(10, 0), 140, 100, "PUBLIC_API"),
        ]
    )
    loaded = load_signal_cycles(cast(Connection, fake), [1, 2], DayType.WEEKDAY)
    assert fake.params == [([1, 2], "WEEKDAY")]
    assert [b.source for b in loaded[1]] == ["DEFAULT_ASSUMPTION", "USER_OBSERVED"]
    assert resolve_cycle(loaded[1], time(8, 15)).cycle == USER
    assert resolve_cycle(loaded[2], time(8, 15)) == ResolvedCycle(PUBLIC, "PUBLIC_API")


def test_load_rejects_unknown_source() -> None:
    fake = FakeConnection([(1, time(0, 0), time(1, 0), 120, 90, "GUESS")])
    with pytest.raises(ValueError, match="GUESS"):
        load_signal_cycles(cast(Connection, fake), [1], DayType.WEEKDAY)


def test_no_signals_skips_the_query() -> None:
    fake = FakeConnection([])
    assert load_signal_cycles(cast(Connection, fake), [], DayType.WEEKDAY) == {}
    assert fake.params == []


def test_walk_leg_uses_the_selected_cycle_per_crossing() -> None:
    # 교차로 1은 사용자 관측 행, 2는 행이 없어 기본값. 대기 평균이 도보 시간에 더해진다.
    leg = RouteLeg(
        id=5,
        seq_order=1,
        leg_type="WALK",
        start_lat=None,
        start_lng=None,
        end_lat=None,
        end_lng=None,
        planned_distance_m=600.0,
        transit_line_id=None,
        transit_line_name=None,
        board_stop_id=None,
        board_stop_name=None,
        alight_stop_id=None,
        alight_stop_name=None,
        planned_travel_sec=None,
        crossings=(SignalCrossing(1, 1, "nt", "Pd"), SignalCrossing(2, 2, "et", "Pd")),
    )
    cycles: dict[int, tuple[CycleBand, ...]] = {
        1: (
            band(time(0, 0), time(23, 59, 59), ASSUMED, "DEFAULT_ASSUMPTION"),
            band(time(8, 0), time(9, 0), USER, "USER_OBSERVED"),
        )
    }
    walk, inputs = _walk_leg(leg, [], cycles, time(8, 30))
    assert [(c.traffic_signal_id, c.resolved.source) for c in inputs.crossings] == [
        (1, "USER_OBSERVED"),
        (2, None),
    ]
    no_signals, _ = _walk_leg(
        dataclasses.replace(leg, crossings=()),
        [],
        {},
        time(8, 30),
    )
    added = walk.duration.mean - no_signals.duration.mean
    assert added == pytest.approx(USER.wait().mean + defaults.DEFAULT_SIGNAL_CYCLE.wait().mean)
    assert added == pytest.approx(40**2 / 200 + 90**2 / 240)
