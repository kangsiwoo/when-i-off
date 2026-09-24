"""typer CLI. 모든 배치는 여기 서브커맨드로 붙고, 같은 입력에 대해 idempotent해야 한다."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

import typer

from whenioff_analytics import defaults
from whenioff_analytics.daytype import KST, UTC, kst_date_of
from whenioff_analytics.io.db import connect
from whenioff_analytics.model.recommend import NoFeasibleVehicleError, TransitLeg
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


if __name__ == "__main__":
    app()
