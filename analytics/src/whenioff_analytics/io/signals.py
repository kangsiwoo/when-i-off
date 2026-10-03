"""`traffic_signal_cycles` 읽기 (신호 주기 모델의 입력)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import time
from typing import Literal

from whenioff_analytics import defaults
from whenioff_analytics.daytype import DayType
from whenioff_analytics.io.db import Connection
from whenioff_analytics.model.distributions import SignalCycle

SignalSource = Literal["USER_OBSERVED", "PUBLIC_API", "DEFAULT_ASSUMPTION"]

SOURCE_PRIORITY: tuple[SignalSource, ...] = ("USER_OBSERVED", "PUBLIC_API", "DEFAULT_ASSUMPTION")
"""시간대가 겹치는 행이 여럿이면 앞 출처를 쓴다.

DATA_MODEL `traffic_signal_cycles`, backend `SignalCycleRules.SOURCE_PRIORITY`와 같다.
"""

_SQL = """
SELECT traffic_signal_id, time_band_start, time_band_end, cycle_duration_sec, red_duration_sec, source
FROM traffic_signal_cycles
WHERE traffic_signal_id = ANY(%s) AND day_type = %s::day_type
ORDER BY traffic_signal_id, time_band_start, id
"""


@dataclass(frozen=True)
class CycleBand:
    time_band_start: time
    time_band_end: time
    cycle: SignalCycle
    source: SignalSource


def load_signal_cycles(
    conn: Connection,
    traffic_signal_ids: list[int],
    day_type: DayType,
) -> dict[int, tuple[CycleBand, ...]]:
    """교차로별로 그 day_type의 주기 행 전부 (출처 무관, 시간대 시작 순)."""
    if not traffic_signal_ids:
        return {}
    bands: dict[int, list[CycleBand]] = {}
    with conn.cursor() as cur:
        cur.execute(_SQL, (traffic_signal_ids, day_type.value))
        for row in cur.fetchall():
            bands.setdefault(int(row[0]), []).append(
                CycleBand(
                    time_band_start=row[1],
                    time_band_end=row[2],
                    cycle=SignalCycle(cycle_sec=float(row[3]), red_sec=float(row[4])),
                    source=_source(str(row[5])),
                )
            )
    return {signal_id: tuple(found) for signal_id, found in bands.items()}


def select_band(bands: tuple[CycleBand, ...], at_kst: time) -> CycleBand | None:
    """`at_kst`를 담는 시간대(`[start, end)`) 중 출처 우선순위가 가장 높은 행. 없으면 None.

    `bands`는 이미 한 day_type으로 좁혀져 있다. 시간대는 자정을 넘지 않는다고 본다
    (V1 스키마 주석, backend 검증). 같은 출처끼리 겹치면(사용자 행은 backend가 막지만 다른 출처는
    막지 않는다) 넘어온 순서(시작 시각 순)의 앞 행.
    """
    containing = [band for band in bands if band.time_band_start <= at_kst < band.time_band_end]
    if not containing:
        return None
    return min(containing, key=lambda band: SOURCE_PRIORITY.index(band.source))


def select_cycle(bands: tuple[CycleBand, ...], at_kst: time) -> SignalCycle | None:
    band = select_band(bands, at_kst)
    return band.cycle if band is not None else None


@dataclass(frozen=True)
class ResolvedCycle:
    cycle: SignalCycle
    source: SignalSource | None
    """None = 담는 행이 없어 `defaults.DEFAULT_SIGNAL_CYCLE`을 썼다."""


def resolve_cycle(bands: tuple[CycleBand, ...], at_kst: time) -> ResolvedCycle:
    """recommend가 쓰는 주기: `select_band`, 담는 행이 없으면 `defaults.DEFAULT_SIGNAL_CYCLE`."""
    band = select_band(bands, at_kst)
    if band is None:
        return ResolvedCycle(cycle=defaults.DEFAULT_SIGNAL_CYCLE, source=None)
    return ResolvedCycle(cycle=band.cycle, source=band.source)


def _source(value: str) -> SignalSource:
    for source in SOURCE_PRIORITY:
        if source == value:
            return source
    raise ValueError(f"unknown signal_data_source {value!r}")
