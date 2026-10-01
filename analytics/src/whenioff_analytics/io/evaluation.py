"""`evaluate`의 입력(추천·trip·탑승 시도·TRANSIT 구간) 읽기와 `recommendation_evaluations` upsert."""

from __future__ import annotations

from collections import defaultdict
from datetime import date

from whenioff_analytics.io.db import Connection
from whenioff_analytics.io.walking import UpsertCounts
from whenioff_analytics.model.evaluation import AttemptRecord, Evaluation, RecommendationRecord, TripRecord

_RANGE = """
(%(from)s::date IS NULL OR {col} >= %(from)s::date) AND (%(to)s::date IS NULL OR {col} <= %(to)s::date)
"""

# "그날 마지막 추천" 고르기는 model(latest_recommendations)이 한다. 범위 안의 추천을 통째로 읽는다.
_RECOMMENDATION_SQL = f"""
SELECT id, commute_route_id, target_date, model_version, target_arrival_at, recommended_leave_home_at,
       computed_at
FROM departure_recommendations
WHERE {_RANGE.format(col="target_date")}
"""

_TRIP_SQL = f"""
SELECT id, commute_route_id, trip_date, left_home_at, arrived_destination_at
FROM commute_trips
WHERE {_RANGE.format(col="trip_date")}
"""

_ATTEMPT_SQL = """
SELECT commute_trip_id, route_leg_id, attempt_seq, result::text, arrived_at_stop_at,
       vehicle_actual_departure_at
FROM boarding_attempts
WHERE commute_trip_id = ANY(%s)
"""

_TRANSIT_LEG_SQL = """
SELECT commute_route_id, id FROM route_legs WHERE commute_route_id = ANY(%s) AND leg_type = 'TRANSIT'
"""

_COLUMNS = (
    "commute_route_id",
    "target_date",
    "model_version",
    "departure_recommendation_id",
    "commute_trip_id",
    "target_arrival_at",
    "recommended_leave_home_at",
    "actual_left_home_at",
    "actual_arrived_at",
    "departure_diff_sec",
    "arrival_diff_sec",
    "is_late",
    "all_legs_caught",
    "missed_count",
    "avg_stop_wait_sec",
)
_VALUES = _COLUMNS[3:]
"""키(앞 셋)를 뺀 값 열. 이 중 하나라도 바뀌어야 갱신한다."""

# 값이 그대로인 행은 건드리지 않아(WHERE ... IS DISTINCT FROM) RETURNING에도 안 나온다.
# evaluated_at도 값이 바뀐 때만 움직인다.
_UPSERT_SQL = f"""
INSERT INTO recommendation_evaluations ({", ".join(_COLUMNS)})
VALUES ({", ".join(["%s"] * len(_COLUMNS))})
ON CONFLICT (commute_route_id, target_date, model_version) DO UPDATE SET
    {", ".join(f"{c} = EXCLUDED.{c}" for c in _VALUES)},
    evaluated_at = now()
WHERE ({", ".join(f"recommendation_evaluations.{c}" for c in _VALUES)})
    IS DISTINCT FROM ({", ".join(f"EXCLUDED.{c}" for c in _VALUES)})
RETURNING (xmax = 0) AS inserted
"""

# 평가한 날짜 범위 안에서 이번에 나오지 않은 키(trip이나 추천이 지워지거나 날짜가 고쳐진 것)의 행을 지운다.
# 범위 밖은 이번에 보지 않았으므로 건드리지 않는다.
_DELETE_STALE_SQL = f"""
DELETE FROM recommendation_evaluations re
WHERE {_RANGE.format(col="re.target_date")}
  AND NOT EXISTS (
      SELECT 1 FROM unnest(%(routes)s::bigint[], %(dates)s::date[], %(versions)s::text[])
          AS kept(route_id, target_date, model_version)
      WHERE kept.route_id = re.commute_route_id
        AND kept.target_date = re.target_date
        AND kept.model_version = re.model_version
  )
"""


def load_recommendations(
    conn: Connection, date_from: date | None, date_to: date | None
) -> list[RecommendationRecord]:
    """`target_date`가 [date_from, date_to](양 끝 포함)인 추천 전부. 경계가 `None`이면 그쪽은 열려 있다."""
    with conn.cursor() as cur:
        cur.execute(_RECOMMENDATION_SQL, {"from": date_from, "to": date_to})
        return [
            RecommendationRecord(
                id=int(row[0]),
                commute_route_id=int(row[1]),
                target_date=row[2],
                model_version=str(row[3]),
                target_arrival_at=row[4],
                recommended_leave_home_at=row[5],
                computed_at=row[6],
            )
            for row in cur.fetchall()
        ]


def load_trips(conn: Connection, date_from: date | None, date_to: date | None) -> list[TripRecord]:
    """`trip_date`가 범위 안인 trip과 그 탑승 시도."""
    with conn.cursor() as cur:
        cur.execute(_TRIP_SQL, {"from": date_from, "to": date_to})
        trips = cur.fetchall()
        if not trips:
            return []
        cur.execute(_ATTEMPT_SQL, ([int(row[0]) for row in trips],))
        attempts: dict[int, list[AttemptRecord]] = defaultdict(list)
        for row in cur.fetchall():
            attempts[int(row[0])].append(
                AttemptRecord(
                    route_leg_id=int(row[1]),
                    attempt_seq=int(row[2]),
                    result=str(row[3]),
                    arrived_at_stop_at=row[4],
                    vehicle_actual_departure_at=row[5],
                )
            )
    return [
        TripRecord(
            id=int(row[0]),
            commute_route_id=int(row[1]),
            trip_date=row[2],
            left_home_at=row[3],
            arrived_destination_at=row[4],
            attempts=tuple(
                sorted(attempts.get(int(row[0]), ()), key=lambda a: (a.route_leg_id, a.attempt_seq))
            ),
        )
        for row in trips
    ]


def load_transit_legs(conn: Connection, route_ids: list[int]) -> dict[int, list[int]]:
    """경로마다 TRANSIT 구간 id. TRANSIT이 없는 경로는 결과에 없다 (빈 목록과 같다)."""
    legs: dict[int, list[int]] = defaultdict(list)
    if not route_ids:
        return legs
    with conn.cursor() as cur:
        cur.execute(_TRANSIT_LEG_SQL, (route_ids,))
        for row in cur.fetchall():
            legs[int(row[0])].append(int(row[1]))
    return legs


def save_evaluations(
    conn: Connection, date_from: date | None, date_to: date | None, evaluations: list[Evaluation]
) -> UpsertCounts:
    """키당 한 행으로 upsert하고, 범위 안에서 이번에 나오지 않은 키의 행은 지운다."""
    created = updated = 0
    with conn.cursor() as cur:
        cur.execute(
            _DELETE_STALE_SQL,
            {
                "from": date_from,
                "to": date_to,
                "routes": [e.commute_route_id for e in evaluations],
                "dates": [e.target_date for e in evaluations],
                "versions": [e.model_version for e in evaluations],
            },
        )
        deleted = max(cur.rowcount, 0)
        for e in evaluations:
            cur.execute(_UPSERT_SQL, tuple(getattr(e, column) for column in _COLUMNS))
            row = cur.fetchone()
            if row is None:
                continue
            if row[0]:
                created += 1
            else:
                updated += 1
    return UpsertCounts(
        created=created, updated=updated, unchanged=len(evaluations) - created - updated, deleted=deleted
    )
