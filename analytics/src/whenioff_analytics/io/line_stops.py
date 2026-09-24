"""`transit_line_stops` 읽기 — 구간(승차→하차)의 방향을 정한다."""

from __future__ import annotations

from whenioff_analytics.io.db import Connection

_SQL = """
SELECT stop_id, direction_code, seq_no
FROM transit_line_stops
WHERE transit_line_id = %s AND stop_id = ANY(%s)
ORDER BY direction_code, seq_no
"""


def resolve_leg_direction(
    conn: Connection,
    transit_line_id: int,
    board_stop_id: int,
    alight_stop_id: int | None,
) -> str | None:
    """backend `LegDirectionResolver`와 같은 규칙: 하차역이 승차역보다 뒤에 오는 방향을 고른다.

    한 정류장은 상·하행 양쪽에 서므로 승차역만으로는 방향이 정해지지 않는다. 하차역을 알면
    순서(`seq_no`)로 판정하고, 모르면 승차역이 속한 방향 중 사전순 첫 번째를 쓴다.
    노선-정류장 순서가 비어 있으면(`transit_line_stops` 미적재) `None`이다.
    """
    stop_ids = list(dict.fromkeys(s for s in (board_stop_id, alight_stop_id) if s is not None))
    with conn.cursor() as cur:
        cur.execute(_SQL, (transit_line_id, stop_ids))
        rows = [(int(row[0]), str(row[1]), int(row[2])) for row in cur.fetchall()]

    board = [(direction, seq_no) for stop_id, direction, seq_no in rows if stop_id == board_stop_id]
    if not board:
        return None
    if alight_stop_id is not None:
        alight: dict[str, list[int]] = {}
        for stop_id, direction, seq_no in rows:
            if stop_id == alight_stop_id:
                alight.setdefault(direction, []).append(seq_no)
        for direction, seq_no in board:
            if any(other > seq_no for other in alight.get(direction, ())):
                return direction
    return min(direction for direction, _ in board)
