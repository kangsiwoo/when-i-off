"""typer CLI. 모든 배치는 여기 서브커맨드로 붙고, 같은 입력에 대해 idempotent해야 한다."""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable
from datetime import date, datetime
from typing import Annotated

import typer

from whenioff_analytics import defaults
from whenioff_analytics.daytype import KST, UTC, kst_date_of
from whenioff_analytics.io.db import connect
from whenioff_analytics.io.walking import load_trip_events, save_walking_segments
from whenioff_analytics.model.recommend import NoFeasibleVehicleError, TransitLeg
from whenioff_analytics.model.walking import SkippedSegment, WalkingSegment, derive_walking_segments
from whenioff_analytics.service import (
    IncompleteLegError,
    NoCandidateVehiclesError,
    RecommendationResult,
    compute_recommendation,
    store,
)

app = typer.Typer(help="when-i-off analytics batches", no_args_is_help=True, add_completion=False)


@app.callback()
def main() -> None:
    """배치가 하나뿐일 때도 typer가 서브커맨드를 벗겨내지 않게 잡아 둔다."""


def parse_moment(raw: str) -> datetime:
    """ISO-8601. 타임존이 없으면 KST 벽시계로 읽는다 (시간표와 같은 감각으로 적을 수 있게)."""
    parsed = datetime.fromisoformat(raw)
    return parsed.replace(tzinfo=KST) if parsed.tzinfo is None else parsed


def _local(moment: datetime) -> str:
    return moment.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S KST")


@app.command()
def recommend(
    route_id: Annotated[int, typer.Option("--route-id", help="commute_routes.id")],
    target_arrival_at: Annotated[
        str,
        typer.Option("--target-arrival-at", help="목표 도착 시각 (ISO-8601, 타임존 없으면 KST)"),
    ],
    probability: Annotated[
        float, typer.Option("--probability", "-p", help="구간별 목표 성공확률")
    ] = defaults.DEFAULT_PROBABILITY,
    lookback_hours: Annotated[
        float, typer.Option("--lookback-hours", help="후보 차량을 목표 시각에서 몇 시간 거슬러 볼지")
    ] = defaults.DEFAULT_LOOKBACK_HOURS,
    dry_run: Annotated[bool, typer.Option("--dry-run", help="계산만 하고 DB에 쓰지 않는다")] = False,
) -> None:
    """목표 도착 시각을 맞추려면 언제 집을 나서야 하는지 계산해 기록한다."""
    target = parse_moment(target_arrival_at)
    if not 0.0 < probability < 1.0:
        typer.echo("--probability must be in (0, 1)", err=True)
        raise typer.Exit(2)

    with connect() as conn:
        try:
            result = compute_recommendation(conn, route_id, target, probability, lookback_hours)
        except (NoFeasibleVehicleError, NoCandidateVehiclesError, IncompleteLegError) as error:
            conn.rollback()
            typer.echo(f"error: {error}", err=True)
            raise typer.Exit(1) from error
        _report(result)
        if dry_run:
            conn.rollback()
            typer.echo("dry-run: nothing written")
            return
        outcome, row_id = store(conn, result)
    typer.echo(f"departure_recommendations id={row_id} ({outcome})")


def _report(result: RecommendationResult) -> None:
    recommendation = result.recommendation
    typer.echo(f"route {result.route.id} ({result.route.name}), p={result.probability}")
    typer.echo(f"  target arrival        {_local(result.target_arrival_at)}")
    typer.echo(
        f"  leave home at         {_local(recommendation.leave_home_at)} "
        f"({recommendation.leave_home_at.astimezone(UTC).isoformat()})"
    )
    typer.echo(f"  catch probability     {recommendation.catch_probability:.4f}")
    typer.echo(f"  buffer                {recommendation.buffer_seconds}s")
    typer.echo(f"  target date           {kst_date_of(result.target_arrival_at)}")
    typer.echo(f"  model version         {defaults.MODEL_VERSION}")
    legs = {leg.route_leg_id: leg for leg in result.legs if isinstance(leg, TransitLeg)}
    for chosen in recommendation.chosen:
        candidates = len(legs[chosen.route_leg_id].candidates)
        typer.echo(
            f"  leg {chosen.route_leg_id}: vehicle {chosen.candidate.label} of {candidates} candidates"
        )
        typer.echo(f"      be at the stop by {_local(chosen.be_at_stop_by)}")
        typer.echo(
            f"      catch p={chosen.catch_probability:.4f}, arrive-in-time p={chosen.arrive_probability:.4f}"
        )


def _parse_date(raw: str | None, option: str) -> date | None:
    if raw is None:
        return None
    try:
        return date.fromisoformat(raw)
    except ValueError as error:
        typer.echo(f"{option} must be YYYY-MM-DD, got {raw!r}", err=True)
        raise typer.Exit(2) from error


@app.command("derive-walking-segments")
def derive_walking_segments_command(
    date_from: Annotated[
        str | None, typer.Option("--from", help="이 trip_date부터 (YYYY-MM-DD, 포함). 없으면 처음부터")
    ] = None,
    date_to: Annotated[
        str | None, typer.Option("--to", help="이 trip_date까지 (YYYY-MM-DD, 포함). 없으면 끝까지")
    ] = None,
    dry_run: Annotated[bool, typer.Option("--dry-run", help="계산만 하고 DB에 쓰지 않는다")] = False,
) -> None:
    """trip·탑승 시도·GPS에서 WALK 구간 실측을 파생해 `walking_segments`에 upsert한다."""
    start, end = _parse_date(date_from, "--from"), _parse_date(date_to, "--to")
    if start is not None and end is not None and start > end:
        typer.echo("--from must not be after --to", err=True)
        raise typer.Exit(2)

    with connect() as conn:
        trips = load_trip_events(conn, start, end)
        segments: list[WalkingSegment] = []
        skipped: list[SkippedSegment] = []
        for trip in trips:
            derived, missed = derive_walking_segments(trip)
            segments += derived
            skipped += missed

        window = f"{start or ''}..{end or ''}" if start or end else "all"
        typer.echo(f"trips {len(trips)} (trip_date {window})")
        typer.echo(f"  derived  {len(segments)}{_breakdown(s.distance_source for s in segments)}")
        typer.echo(f"  skipped  {len(skipped)}{_breakdown(s.reason for s in skipped)}")
        if dry_run:
            conn.rollback()
            typer.echo("dry-run: nothing written")
            return
        counts = save_walking_segments(conn, [t.commute_trip_id for t in trips], segments)
    typer.echo(
        f"walking_segments: created {counts.created}, updated {counts.updated}, "
        f"unchanged {counts.unchanged}, deleted {counts.deleted}"
    )


def _breakdown(values: Iterable[str]) -> str:
    counted = Counter(values)
    return f" ({', '.join(f'{key} {n}' for key, n in sorted(counted.items()))})" if counted else ""


if __name__ == "__main__":
    app()
