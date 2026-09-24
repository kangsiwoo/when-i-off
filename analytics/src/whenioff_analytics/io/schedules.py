"""`transit_schedules` 읽기 — KST 벽시계 시간표를 절대 시각 후보 차량으로 편다."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from whenioff_analytics.daytype import DayType, kst_date_of, kst_wall_clock_to_instant, resolve_day_type
from whenioff_analytics.io.db import Connection

_SQL = """
SELECT scheduled_time
FROM transit_schedules
WHERE transit_line_id = %s AND stop_id = %s AND day_type = %s::day_type AND direction_code = %s
ORDER BY scheduled_time
"""


@dataclass(frozen=True)
class ScheduledDeparture:
    service_date: date
    day_type: DayType
    scheduled_time: time
    departure_at: datetime


def load_scheduled_departures(
    conn: Connection,
    transit_line_id: int,
    stop_id: int,
    direction_code: str,
    window_start: datetime,
    window_end: datetime,
) -> tuple[ScheduledDeparture, ...]:
    """[window_start, window_end] 안에 승차역을 `direction_code` 방향으로 떠나는 시간표상의 차량.

    시간표는 "KST 하루 중 시각"의 반복이라 운행일을 붙여야 절대 시각이 된다. 창이 자정을 넘으면
    날짜마다 `day_type`을 다시 판정한다 — 금→토, 일→월, 공휴일 전날에 시간표가 바뀌기 때문이다.

    `direction_code`는 기본값 없는 필수 인자다. 한 정류장에는 상·하행이 같이 서므로, 생략을
    허용하면 반대 방향 차가 조용히 후보에 섞인다 (`transit_line_stops`와 같은 어휘, #26).
    """
    if window_end < window_start:
        raise ValueError("window_end must not be before window_start")

    by_day_type: dict[DayType, tuple[time, ...]] = {}
    departures: list[ScheduledDeparture] = []
    service_date = kst_date_of(window_start)
    last_date = kst_date_of(window_end)
    with conn.cursor() as cur:
        while service_date <= last_date:
            day_type = resolve_day_type(service_date)
            if day_type not in by_day_type:
                cur.execute(_SQL, (transit_line_id, stop_id, day_type.value, direction_code))
                by_day_type[day_type] = tuple(row[0] for row in cur.fetchall())
            for scheduled_time in by_day_type[day_type]:
                departure_at = kst_wall_clock_to_instant(service_date, scheduled_time)
                if window_start <= departure_at <= window_end:
                    departures.append(
                        ScheduledDeparture(
                            service_date=service_date,
                            day_type=day_type,
                            scheduled_time=scheduled_time,
                            departure_at=departure_at,
                        )
                    )
            service_date += timedelta(days=1)

    return tuple(sorted(departures, key=lambda d: d.departure_at))
