"""`departure_recommendations` 쓰기."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from enum import StrEnum

from whenioff_analytics.io.db import Connection


class SaveOutcome(StrEnum):
    CREATED = "created"
    APPENDED = "appended"
    UNCHANGED = "unchanged"


@dataclass(frozen=True)
class RecommendationRow:
    user_id: int
    commute_route_id: int
    target_date: date
    target_arrival_at: datetime
    recommended_leave_home_at: datetime
    catch_probability: float
    buffer_seconds: int
    model_version: str


_SELECT_SQL = """
SELECT id, recommended_leave_home_at, catch_probability, buffer_seconds
FROM departure_recommendations
WHERE commute_route_id = %s AND target_date = %s AND target_arrival_at = %s AND model_version = %s
ORDER BY id DESC
LIMIT 1
FOR UPDATE
"""

_INSERT_SQL = """
INSERT INTO departure_recommendations
    (user_id, commute_route_id, target_date, target_arrival_at,
     recommended_leave_home_at, catch_probability, buffer_seconds, model_version)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
RETURNING id
"""


def save_recommendation(conn: Connection, row: RecommendationRow) -> tuple[SaveOutcome, int]:
    """추천을 적재한다. **기존 행을 덮어쓰지 않는다.**

    DATA_MODEL의 `departure_recommendations`는 "과거 추천도 삭제하지 않고 누적해서 같은 날짜의
    commute_trips와 비교해 모델 성능을 추적한다"고 못박는다. 그래서 값이 달라졌으면 UPDATE가
    아니라 새 행을 덧붙인다 — 실시간 예측이 갱신되며 재계산될 때 그 변화 이력이 남아야 오차
    학습이 가능하다.

    동시에 CONVENTIONS의 "같은 날짜에 두 번 돌려도 결과 동일"도 지켜야 하므로, 같은 (경로,
    목표일, 목표 도착 시각, 모델 버전)의 **최신 행과 값이 같으면 아무것도 쓰지 않는다**.
    그래서 같은 입력을 반복해도 행이 늘지 않고, 값이 실제로 바뀔 때만 이력이 쌓인다.
    """
    with conn.cursor() as cur:
        cur.execute(
            _SELECT_SQL,
            (row.commute_route_id, row.target_date, row.target_arrival_at, row.model_version),
        )
        existing = cur.fetchone()
        if existing is not None:
            unchanged = (
                existing[1] == row.recommended_leave_home_at
                and float(existing[2]) == row.catch_probability
                and int(existing[3]) == row.buffer_seconds
            )
            if unchanged:
                return SaveOutcome.UNCHANGED, int(existing[0])

        cur.execute(
            _INSERT_SQL,
            (
                row.user_id,
                row.commute_route_id,
                row.target_date,
                row.target_arrival_at,
                row.recommended_leave_home_at,
                row.catch_probability,
                row.buffer_seconds,
                row.model_version,
            ),
        )
        inserted = cur.fetchone()
        if inserted is None:
            raise RuntimeError("INSERT ... RETURNING id returned no row")
        outcome = SaveOutcome.CREATED if existing is None else SaveOutcome.APPENDED
        return outcome, int(inserted[0])
