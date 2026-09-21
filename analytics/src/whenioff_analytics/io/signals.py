"""`traffic_signal_cycles` 읽기 (신호 주기 모델의 입력)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import time

from whenioff_analytics.daytype import DayType
from whenioff_analytics.io.db import Connection
from whenioff_analytics.model.distributions import SignalCycle

_SQL = """
SELECT traffic_signal_id, time_band_start, time_band_end, cycle_duration_sec, red_duration_sec
FROM traffic_signal_cycles
WHERE traffic_signal_id = ANY(%s) AND day_type = %s::day_type
ORDER BY traffic_signal_id, time_band_start
"""


@dataclass(frozen=True)
class CycleBand:
    time_band_start: time
    time_band_end: time
    cycle: SignalCycle


def load_signal_cycles(
    conn: Connection,
    traffic_signal_ids: list[int],
    day_type: DayType,
) -> dict[int, tuple[CycleBand, ...]]:
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
                )
            )
    return {signal_id: tuple(found) for signal_id, found in bands.items()}


def select_cycle(bands: tuple[CycleBand, ...], at_kst: time) -> SignalCycle | None:
    """시간대는 자정을 넘지 않는다고 본다 (V1 스키마 주석과 같은 가정)."""
    for band in bands:
        if band.time_band_start <= at_kst < band.time_band_end:
            return band.cycle
    return None
