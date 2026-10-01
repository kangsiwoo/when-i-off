"""`walking_segments` 파생의 입력(trip·구간·시도·GPS) 읽기와 결과 upsert."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from typing import Any

from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.routes import to_point
from whenioff_analytics.model.walking import Attempt, TracePoint, TripEvents, TripLeg, WalkingSegment

_TRIP_SQL = """
SELECT id, commute_route_id, left_home_at, arrived_destination_at
FROM commute_trips
WHERE (%(from)s::date IS NULL OR trip_date >= %(from)s::date)
  AND (%(to)s::date IS NULL OR trip_date <= %(to)s::date)
ORDER BY id
"""

_LEG_SQL = """
SELECT commute_route_id, id, leg_type::text, planned_distance_m, start_lat, start_lng, end_lat, end_lng
FROM route_legs
WHERE commute_route_id = ANY(%s)
ORDER BY commute_route_id, seq_order
"""

_ATTEMPT_SQL = """
SELECT commute_trip_id, route_leg_id, attempt_seq, result::text, arrived_at_stop_at, alighted_at
FROM boarding_attempts
WHERE commute_trip_id = ANY(%s)
"""

_TRACE_SQL = """
SELECT commute_trip_id, recorded_at, lat, lng, accuracy_m
FROM gps_traces
WHERE commute_trip_id = ANY(%s)
"""

# 값이 그대로인 행은 건드리지 않아(WHERE ... IS DISTINCT FROM) RETURNING에도 안 나온다.
# 그래서 반환 행 수로 생성/갱신/불변을 셀 수 있고, created_at도 처음 파생한 시각으로 남는다.
_UPSERT_SQL = """
INSERT INTO walking_segments
    (commute_trip_id, route_leg_id, started_at, ended_at, duration_sec, distance_m, avg_speed_mps)
VALUES (%s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (commute_trip_id, route_leg_id) DO UPDATE SET
    started_at = EXCLUDED.started_at,
    ended_at = EXCLUDED.ended_at,
    duration_sec = EXCLUDED.duration_sec,
    distance_m = EXCLUDED.distance_m,
    avg_speed_mps = EXCLUDED.avg_speed_mps
WHERE (walking_segments.started_at, walking_segments.ended_at, walking_segments.duration_sec,
       walking_segments.distance_m, walking_segments.avg_speed_mps)
    IS DISTINCT FROM
      (EXCLUDED.started_at, EXCLUDED.ended_at, EXCLUDED.duration_sec,
       EXCLUDED.distance_m, EXCLUDED.avg_speed_mps)
RETURNING (xmax = 0) AS inserted
"""

# 이번에 처리한 trip에서 더는 파생되지 않는 구간(입력이 고쳐져 건너뛰게 된 것)의 옛 행을 지운다.
# 남겨 두면 calibrate가 이미 틀렸다고 판명된 실측을 계속 쓴다.
_DELETE_STALE_SQL = """
DELETE FROM walking_segments ws
WHERE ws.commute_trip_id = ANY(%s)
  AND NOT EXISTS (
      SELECT 1 FROM unnest(%s::bigint[], %s::bigint[]) AS kept(trip_id, leg_id)
      WHERE kept.trip_id = ws.commute_trip_id AND kept.leg_id = ws.route_leg_id
  )
"""


@dataclass(frozen=True)
class UpsertCounts:
    created: int
    updated: int
    unchanged: int
    deleted: int


def load_trip_events(conn: Connection, date_from: date | None, date_to: date | None) -> list[TripEvents]:
    """`trip_date`가 [date_from, date_to](양 끝 포함)인 trip. 경계가 `None`이면 그쪽은 열려 있다."""
    with conn.cursor() as cur:
        cur.execute(_TRIP_SQL, {"from": date_from, "to": date_to})
        trips = cur.fetchall()
        if not trips:
            return []
        trip_ids = [int(row[0]) for row in trips]
        route_ids = sorted({int(row[1]) for row in trips})

        cur.execute(_ATTEMPT_SQL, (trip_ids,))
        attempts: dict[tuple[int, int], list[Attempt]] = defaultdict(list)
        for row in cur.fetchall():
            attempts[(int(row[0]), int(row[1]))].append(
                Attempt(
                    attempt_seq=int(row[2]), result=str(row[3]), arrived_at_stop_at=row[4], alighted_at=row[5]
                )
            )

        cur.execute(_LEG_SQL, (route_ids,))
        legs: dict[int, list[tuple[Any, ...]]] = defaultdict(list)
        for row in cur.fetchall():
            legs[int(row[0])].append(row)

        cur.execute(_TRACE_SQL, (trip_ids,))
        traces: dict[int, list[TracePoint]] = defaultdict(list)
        for row in cur.fetchall():
            traces[int(row[0])].append(
                TracePoint(
                    recorded_at=row[1],
                    lat=float(row[2]),
                    lng=float(row[3]),
                    accuracy_m=None if row[4] is None else float(row[4]),
                )
            )

    return [
        TripEvents(
            commute_trip_id=int(trip_id),
            left_home_at=left_home_at,
            arrived_destination_at=arrived_destination_at,
            legs=tuple(
                TripLeg(
                    route_leg_id=int(leg[1]),
                    leg_type=str(leg[2]),
                    planned_distance_m=None if leg[3] is None else float(leg[3]),
                    start=to_point(leg[4], leg[5]),
                    end=to_point(leg[6], leg[7]),
                    attempts=tuple(attempts.get((int(trip_id), int(leg[1])), ())),
                )
                for leg in legs[int(route_id)]
            ),
            traces=tuple(traces.get(int(trip_id), ())),
        )
        for trip_id, route_id, left_home_at, arrived_destination_at in trips
    ]


def save_walking_segments(
    conn: Connection, trip_ids: list[int], segments: list[WalkingSegment]
) -> UpsertCounts:
    """`(commute_trip_id, route_leg_id)`당 한 행으로 upsert한다. 같은 입력이면 다시 돌려도 결과가 같다.

    `trip_ids`(이번에 읽은 trip)에 붙은 행 중 `segments`에 없는 것은 지운다 — 다시 돌린 결과가
    처음부터 돌린 결과와 같아야 하기 때문이다.
    """
    created = updated = 0
    with conn.cursor() as cur:
        cur.execute(
            _DELETE_STALE_SQL,
            (trip_ids, [s.commute_trip_id for s in segments], [s.route_leg_id for s in segments]),
        )
        deleted = max(cur.rowcount, 0)
        for s in segments:
            cur.execute(
                _UPSERT_SQL,
                (
                    s.commute_trip_id,
                    s.route_leg_id,
                    s.started_at,
                    s.ended_at,
                    s.duration_sec,
                    s.distance_m,
                    s.avg_speed_mps,
                ),
            )
            row = cur.fetchone()
            if row is None:
                continue
            if row[0]:
                created += 1
            else:
                updated += 1
    return UpsertCounts(
        created=created, updated=updated, unchanged=len(segments) - created - updated, deleted=deleted
    )
