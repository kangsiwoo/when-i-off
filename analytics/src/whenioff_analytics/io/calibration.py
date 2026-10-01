"""`calibrate`의 입력(도보 실측·탑승 시도) 읽기와 세 보정 테이블 upsert.

`calibrate`는 매번 **전체 데이터**로 다시 계산한다. 그래서 이번 결과에 없는 그룹의 행(입력이
사라지거나 고쳐져 더는 나오지 않는 그룹)은 테이블 전체에서 지운다 — 다시 돌린 결과가 처음부터
돌린 결과와 같아야 하기 때문이다.
"""

from __future__ import annotations

from collections.abc import Sequence
from collections.abc import Set as AbstractSet
from typing import Any

from psycopg import sql

from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.walking import UpsertCounts
from whenioff_analytics.model.calibration import (
    AttemptSample,
    BandKey,
    PredictionCalibration,
    TravelTimeCalibration,
    WalkingProfile,
    WalkSample,
)

_WALK_SQL = """
SELECT t.user_id, ws.route_leg_id, ws.commute_trip_id, ws.started_at, ws.avg_speed_mps
FROM walking_segments ws
JOIN commute_trips t ON t.id = ws.commute_trip_id
"""

# TRANSIT 구간만 노선이 있다 (chk_leg_fields). 시도는 TRANSIT 구간에만 생기지만 조인으로 한 번 더 거른다.
_ATTEMPT_SQL = """
SELECT ba.id, rl.transit_line_id, rl.board_stop_id, rl.alight_stop_id, ba.result::text,
       ba.vehicle_scheduled_or_predicted_at, ba.vehicle_actual_departure_at, ba.alighted_at
FROM boarding_attempts ba
JOIN route_legs rl ON rl.id = ba.route_leg_id
WHERE rl.transit_line_id IS NOT NULL
"""

# 값이 그대로인 행은 건드리지 않아(WHERE ... IS DISTINCT FROM) RETURNING에도 안 나온다.
# updated_at도 값이 바뀐 때만 움직인다.
_UPSERT_WALKING_SQL = """
INSERT INTO user_walking_profile (user_id, route_leg_id, avg_speed_mps, stddev_speed_mps, sample_count)
VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (user_id, route_leg_id) DO UPDATE SET
    avg_speed_mps = EXCLUDED.avg_speed_mps,
    stddev_speed_mps = EXCLUDED.stddev_speed_mps,
    sample_count = EXCLUDED.sample_count,
    updated_at = now()
WHERE (user_walking_profile.avg_speed_mps, user_walking_profile.stddev_speed_mps,
       user_walking_profile.sample_count)
    IS DISTINCT FROM (EXCLUDED.avg_speed_mps, EXCLUDED.stddev_speed_mps, EXCLUDED.sample_count)
RETURNING (xmax = 0) AS inserted
"""

_UPSERT_PREDICTION_SQL = """
INSERT INTO transit_prediction_calibration
    (transit_line_id, stop_id, day_type, time_band_start, time_band_end, bias_sec, stddev_sec, sample_count)
VALUES (%s, %s, %s::day_type, %s, %s, %s, %s, %s)
ON CONFLICT (transit_line_id, stop_id, day_type, time_band_start, time_band_end) DO UPDATE SET
    bias_sec = EXCLUDED.bias_sec,
    stddev_sec = EXCLUDED.stddev_sec,
    sample_count = EXCLUDED.sample_count,
    updated_at = now()
WHERE (transit_prediction_calibration.bias_sec, transit_prediction_calibration.stddev_sec,
       transit_prediction_calibration.sample_count)
    IS DISTINCT FROM (EXCLUDED.bias_sec, EXCLUDED.stddev_sec, EXCLUDED.sample_count)
RETURNING (xmax = 0) AS inserted
"""

_UPSERT_TRAVEL_SQL = """
INSERT INTO transit_travel_time_calibration
    (transit_line_id, board_stop_id, alight_stop_id, day_type, time_band_start, time_band_end,
     mean_sec, stddev_sec, sample_count)
VALUES (%s, %s, %s, %s::day_type, %s, %s, %s, %s, %s)
ON CONFLICT (transit_line_id, board_stop_id, alight_stop_id, day_type, time_band_start, time_band_end)
DO UPDATE SET
    mean_sec = EXCLUDED.mean_sec,
    stddev_sec = EXCLUDED.stddev_sec,
    sample_count = EXCLUDED.sample_count,
    updated_at = now()
WHERE (transit_travel_time_calibration.mean_sec, transit_travel_time_calibration.stddev_sec,
       transit_travel_time_calibration.sample_count)
    IS DISTINCT FROM (EXCLUDED.mean_sec, EXCLUDED.stddev_sec, EXCLUDED.sample_count)
RETURNING (xmax = 0) AS inserted
"""

# 지울 행은 "지금 있는 키 − 이번에 낸 키"다. 테이블이 작아(그룹 수 = 노선 × 정류장 × 밴드) 키를 한 번에
# 읽어 Python에서 고른다. NULL 구간(전역 행)과 enum·TIME 비교를 SQL 배열로 넘기는 것보다 단순하다.
_WALKING_KEYS_SQL = "SELECT id, user_id, route_leg_id FROM user_walking_profile"
_PREDICTION_KEYS_SQL = """
SELECT id, transit_line_id, stop_id, day_type::text, time_band_start, time_band_end
FROM transit_prediction_calibration
"""
_TRAVEL_KEYS_SQL = """
SELECT id, transit_line_id, board_stop_id, alight_stop_id, day_type::text, time_band_start, time_band_end
FROM transit_travel_time_calibration
"""


def load_walk_samples(conn: Connection) -> list[WalkSample]:
    with conn.cursor() as cur:
        cur.execute(_WALK_SQL)
        return [
            WalkSample(
                user_id=int(row[0]),
                route_leg_id=int(row[1]),
                commute_trip_id=int(row[2]),
                started_at=row[3],
                avg_speed_mps=float(row[4]),
            )
            for row in cur.fetchall()
        ]


def load_attempt_samples(conn: Connection) -> list[AttemptSample]:
    with conn.cursor() as cur:
        cur.execute(_ATTEMPT_SQL)
        return [
            AttemptSample(
                attempt_id=int(row[0]),
                transit_line_id=int(row[1]),
                board_stop_id=int(row[2]),
                alight_stop_id=int(row[3]),
                result=str(row[4]),
                predicted_at=row[5],
                actual_departure_at=row[6],
                alighted_at=row[7],
            )
            for row in cur.fetchall()
        ]


def save_walking_profiles(conn: Connection, profiles: list[WalkingProfile]) -> UpsertCounts:
    keys = {(p.user_id, p.route_leg_id) for p in profiles}
    params = [
        (p.user_id, p.route_leg_id, p.avg_speed_mps, p.stddev_speed_mps, p.sample_count) for p in profiles
    ]
    return _sync(conn, _WALKING_KEYS_SQL, keys, _UPSERT_WALKING_SQL, params, "user_walking_profile")


def save_prediction_calibrations(conn: Connection, rows: list[PredictionCalibration]) -> UpsertCounts:
    keys = {(r.transit_line_id, r.stop_id, *_band(r.band)) for r in rows}
    params = [
        (r.transit_line_id, r.stop_id, *_band(r.band), r.bias_sec, r.stddev_sec, r.sample_count) for r in rows
    ]
    return _sync(
        conn, _PREDICTION_KEYS_SQL, keys, _UPSERT_PREDICTION_SQL, params, "transit_prediction_calibration"
    )


def save_travel_time_calibrations(conn: Connection, rows: list[TravelTimeCalibration]) -> UpsertCounts:
    keys = {(r.transit_line_id, r.board_stop_id, r.alight_stop_id, *_band(r.band)) for r in rows}
    params = [
        (
            r.transit_line_id,
            r.board_stop_id,
            r.alight_stop_id,
            *_band(r.band),
            r.mean_sec,
            r.stddev_sec,
            r.sample_count,
        )
        for r in rows
    ]
    return _sync(conn, _TRAVEL_KEYS_SQL, keys, _UPSERT_TRAVEL_SQL, params, "transit_travel_time_calibration")


def _band(band: BandKey) -> tuple[str, Any, Any]:
    return str(band.day_type), band.time_band_start, band.time_band_end


def _sync(
    conn: Connection,
    keys_sql: str,
    keys: AbstractSet[tuple[Any, ...]],
    upsert_sql: str,
    params: Sequence[tuple[Any, ...]],
    table: str,
) -> UpsertCounts:
    """이번에 낸 키에 없는 행을 지우고, 나머지는 upsert한다. 키의 모양은 `keys_sql`의 id 뒤 열과 같다."""
    created = updated = 0
    with conn.cursor() as cur:
        cur.execute(keys_sql)
        stale = [int(row[0]) for row in cur.fetchall() if tuple(row[1:]) not in keys]
        deleted = 0
        if stale:
            cur.execute(sql.SQL("DELETE FROM {} WHERE id = ANY(%s)").format(sql.Identifier(table)), (stale,))
            deleted = max(cur.rowcount, 0)
        for values in params:
            cur.execute(upsert_sql, values)
            row = cur.fetchone()
            if row is None:
                continue
            if row[0]:
                created += 1
            else:
                updated += 1
    return UpsertCounts(
        created=created, updated=updated, unchanged=len(params) - created - updated, deleted=deleted
    )
