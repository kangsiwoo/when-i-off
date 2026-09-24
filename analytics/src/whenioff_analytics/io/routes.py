"""`commute_routes` / `route_legs` / `route_leg_signal_crossings` 읽기."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from whenioff_analytics.io.db import Connection


class RouteNotFoundError(Exception):
    def __init__(self, route_id: int) -> None:
        super().__init__(f"commute_route {route_id} not found")
        self.route_id = route_id


@dataclass(frozen=True)
class SignalCrossing:
    traffic_signal_id: int
    seq_order: int
    approach_dir: str
    signal_kind: str


@dataclass(frozen=True)
class RouteLeg:
    id: int
    seq_order: int
    leg_type: str
    start_lat: float | None
    start_lng: float | None
    end_lat: float | None
    end_lng: float | None
    planned_distance_m: float | None
    transit_line_id: int | None
    transit_line_name: str | None
    board_stop_id: int | None
    board_stop_name: str | None
    alight_stop_id: int | None
    alight_stop_name: str | None
    planned_travel_sec: int | None
    crossings: tuple[SignalCrossing, ...]


@dataclass(frozen=True)
class CommuteRoute:
    id: int
    user_id: int
    name: str
    legs: tuple[RouteLeg, ...]


_LEG_SQL = """
SELECT l.id, l.seq_order, l.leg_type::text,
       l.start_lat, l.start_lng, l.end_lat, l.end_lng, l.planned_distance_m,
       l.transit_line_id, tl.name,
       l.board_stop_id, bs.name,
       l.alight_stop_id, als.name,
       l.planned_travel_sec
FROM route_legs l
LEFT JOIN transit_lines tl ON tl.id = l.transit_line_id
LEFT JOIN transit_stops bs ON bs.id = l.board_stop_id
LEFT JOIN transit_stops als ON als.id = l.alight_stop_id
WHERE l.commute_route_id = %s
ORDER BY l.seq_order
"""

_CROSSING_SQL = """
SELECT route_leg_id, traffic_signal_id, seq_order, approach_dir, signal_kind
FROM route_leg_signal_crossings
WHERE route_leg_id = ANY(%s)
ORDER BY route_leg_id, seq_order
"""


def load_route(conn: Connection, route_id: int) -> CommuteRoute:
    with conn.cursor() as cur:
        cur.execute("SELECT id, user_id, name FROM commute_routes WHERE id = %s", (route_id,))
        head = cur.fetchone()
        if head is None:
            raise RouteNotFoundError(route_id)
        cur.execute(_LEG_SQL, (route_id,))
        leg_rows = cur.fetchall()
        leg_ids = [int(row[0]) for row in leg_rows]
        crossings: dict[int, list[SignalCrossing]] = {leg_id: [] for leg_id in leg_ids}
        if leg_ids:
            cur.execute(_CROSSING_SQL, (leg_ids,))
            for row in cur.fetchall():
                crossings[int(row[0])].append(
                    SignalCrossing(
                        traffic_signal_id=int(row[1]),
                        seq_order=int(row[2]),
                        approach_dir=row[3],
                        signal_kind=row[4],
                    )
                )

    legs = tuple(
        RouteLeg(
            id=int(row[0]),
            seq_order=int(row[1]),
            leg_type=str(row[2]),
            start_lat=_opt_float(row[3]),
            start_lng=_opt_float(row[4]),
            end_lat=_opt_float(row[5]),
            end_lng=_opt_float(row[6]),
            planned_distance_m=_opt_float(row[7]),
            transit_line_id=_opt_int(row[8]),
            transit_line_name=_opt_str(row[9]),
            board_stop_id=_opt_int(row[10]),
            board_stop_name=_opt_str(row[11]),
            alight_stop_id=_opt_int(row[12]),
            alight_stop_name=_opt_str(row[13]),
            planned_travel_sec=_opt_int(row[14]),
            crossings=tuple(crossings[int(row[0])]),
        )
        for row in leg_rows
    )
    return CommuteRoute(id=int(head[0]), user_id=int(head[1]), name=str(head[2]), legs=legs)


def _opt_float(value: Any) -> float | None:
    return None if value is None else float(value)


def _opt_int(value: Any) -> int | None:
    return None if value is None else int(value)


def _opt_str(value: Any) -> str | None:
    return None if value is None else str(value)
