"""typer CLI. 모든 배치는 여기 서브커맨드로 붙고, 같은 입력에 대해 idempotent해야 한다."""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable
from datetime import date, datetime
from typing import Annotated, NoReturn

import typer

from whenioff_analytics import defaults
from whenioff_analytics.daytype import KST, UTC, kst_date_of
from whenioff_analytics.io.calibration import (
    load_attempt_samples,
    load_walk_samples,
    save_prediction_calibrations,
    save_travel_time_calibrations,
    save_walking_profiles,
)
from whenioff_analytics.io.db import connect
from whenioff_analytics.io.evaluation import (
    load_recommendations,
    load_transit_legs,
    load_trips,
    save_evaluations,
)
from whenioff_analytics.io.routes import RouteNotFoundError, load_active_route_targets
from whenioff_analytics.io.walking import UpsertCounts, load_trip_events, save_walking_segments
from whenioff_analytics.model.calibration import (
    prediction_calibrations,
    travel_time_calibrations,
    walking_profiles,
)
from whenioff_analytics.model.evaluation import (
    VersionSummary,
    evaluate_all,
    latest_recommendations,
    summarize,
)
from whenioff_analytics.model.lookup import Provenance, Resolved
from whenioff_analytics.model.recommend import NoFeasibleVehicleError, WalkLeg
from whenioff_analytics.model.targets import Skipped, plan_targets
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
    route_id: Annotated[int | None, typer.Option("--route-id", help="commute_routes.id (경로 하나)")] = None,
    target_arrival_at: Annotated[
        str | None,
        typer.Option("--target-arrival-at", help="목표 도착 시각 (ISO-8601, 타임존 없으면 KST)"),
    ] = None,
    all_active_routes: Annotated[
        bool,
        typer.Option(
            "--all-active-routes",
            help="활성 경로마다 경로의 기본 목표 도착 시각으로 계산 (--route-id/--target-arrival-at 대신)",
        ),
    ] = False,
    on_date: Annotated[
        str | None,
        typer.Option("--date", help="--all-active-routes의 운행일 (YYYY-MM-DD). 없으면 오늘(KST)"),
    ] = None,
    only_in_window: Annotated[
        bool,
        typer.Option("--only-in-window", help="목표 −120분 ~ +30분 창 안인 경로만 계산 (cron용)"),
    ] = False,
    now_at: Annotated[
        str | None,
        typer.Option(
            "--now",
            help="창 판정·기본 날짜의 '지금' (ISO-8601, 타임존 없으면 KST). 테스트·backfill용",
        ),
    ] = None,
    probability: Annotated[
        float, typer.Option("--probability", "-p", help="구간별 목표 성공확률")
    ] = defaults.DEFAULT_PROBABILITY,
    lookback_hours: Annotated[
        float, typer.Option("--lookback-hours", help="후보 차량을 목표 시각에서 몇 시간 거슬러 볼지")
    ] = defaults.DEFAULT_LOOKBACK_HOURS,
    dry_run: Annotated[bool, typer.Option("--dry-run", help="계산만 하고 DB에 쓰지 않는다")] = False,
) -> None:
    """목표 도착 시각을 맞추려면 언제 집을 나서야 하는지 계산해 기록한다.

    경로 하나(`--route-id` + `--target-arrival-at`) 또는 활성 경로 전부(`--all-active-routes`).
    """
    if not 0.0 < probability < 1.0:
        _usage("--probability must be in (0, 1)")
    if all_active_routes:
        if route_id is not None or target_arrival_at is not None:
            _usage("--all-active-routes cannot be combined with --route-id/--target-arrival-at")
        now = _parse_moment_option(now_at, "--now") or datetime.now(UTC)
        service_date = _parse_date(on_date, "--date")
        _recommend_all(now, service_date, only_in_window, probability, lookback_hours, dry_run)
        return
    if on_date is not None or only_in_window or now_at is not None:
        _usage("--date/--only-in-window/--now need --all-active-routes")
    if route_id is None or target_arrival_at is None:
        _usage("give --route-id and --target-arrival-at, or --all-active-routes")
    target = _parse_moment_option(target_arrival_at, "--target-arrival-at")
    assert target is not None

    with connect() as conn:
        try:
            result = compute_recommendation(conn, route_id, target, probability, lookback_hours)
        except RECOMMEND_ERRORS as error:
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


RECOMMEND_ERRORS = (NoFeasibleVehicleError, NoCandidateVehiclesError, IncompleteLegError, RouteNotFoundError)
"""경로 하나의 계산이 실패한 것. 일괄 모드에서는 그 경로만 실패로 세고 다음 경로로 간다."""


def _recommend_all(
    now: datetime,
    service_date: date | None,
    only_in_window: bool,
    probability: float,
    lookback_hours: float,
    dry_run: bool,
) -> None:
    """활성 경로마다 기본 목표로 계산한다 (#68).

    경로마다 따로 커밋해 한 경로의 실패가 다른 경로를 막지 않는다.

    종료 코드: 계산을 시도한 경로가 있고 그 **전부**가 실패했을 때만 1. 시도한 경로가 없으면(전부 건너뜀) 0.
    """
    with connect() as conn:
        routes = load_active_route_targets(conn)
        conn.rollback()
        plans = plan_targets(routes, now, service_date, only_in_window)
        mode = " (only in window)" if only_in_window else ""
        typer.echo(f"all active routes: {len(routes)} at {_local(now)}{mode}, date {service_date or 'auto'}")
        ok = failed = 0
        for plan in plans:
            if isinstance(plan, Skipped):
                typer.echo(
                    f"route {plan.route.route_id} ({plan.route.name}): skipped {plan.reason}: {plan.detail}"
                )
                continue
            try:
                with conn.transaction():
                    result = compute_recommendation(
                        conn, plan.route.route_id, plan.target_arrival_at, probability, lookback_hours
                    )
                    _report(result)
                    if dry_run:
                        typer.echo("dry-run: nothing written")
                    else:
                        outcome, row_id = store(conn, result)
                        typer.echo(f"departure_recommendations id={row_id} ({outcome})")
            except RECOMMEND_ERRORS as error:
                failed += 1
                typer.echo(f"route {plan.route.route_id} ({plan.route.name}): error: {error}", err=True)
                continue
            ok += 1
        skipped = sum(isinstance(plan, Skipped) for plan in plans)
        typer.echo(f"done: computed {ok}, failed {failed}, skipped {skipped}")
    if failed > 0 and ok == 0:
        raise typer.Exit(1)


def _usage(message: str) -> NoReturn:
    typer.echo(message, err=True)
    raise typer.Exit(2)


def _parse_moment_option(raw: str | None, option: str) -> datetime | None:
    if raw is None:
        return None
    try:
        return parse_moment(raw)
    except ValueError as error:
        typer.echo(f"{option} must be ISO-8601, got {raw!r}", err=True)
        raise typer.Exit(2) from error


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
    chosen_by_leg = {chosen.route_leg_id: chosen for chosen in recommendation.chosen}
    for leg, inputs in zip(result.legs, result.inputs, strict=True):
        if isinstance(leg, WalkLeg):
            if inputs.walking_speed is not None:
                speed = _resolved(inputs.walking_speed, "m/s", 2)
                typer.echo(f"  leg {leg.route_leg_id}: walk, speed {speed}")
            continue
        chosen = chosen_by_leg[leg.route_leg_id]
        used = result.candidate_inputs(leg.route_leg_id, chosen.candidate)
        candidates = len(leg.candidates)
        typer.echo(f"  leg {leg.route_leg_id}: vehicle {chosen.candidate.label} of {candidates} candidates")
        typer.echo(f"      be at the stop by {_local(chosen.be_at_stop_by)}")
        typer.echo(
            f"      catch p={chosen.catch_probability:.4f}, arrive-in-time p={chosen.arrive_probability:.4f}"
        )
        typer.echo(
            f"      prediction error {_resolved(used.prediction_error, 's', 0)}; "
            f"travel {_resolved(used.travel_time, 's', 0)}"
        )


def _resolved(resolved: Resolved, unit: str, digits: int) -> str:
    """`1.31±0.08m/s calibrated n=12` 처럼 값·출처·샘플 수를 한 덩어리로."""
    value = resolved.value
    count = "" if resolved.provenance is Provenance.DEFAULT else f" n={resolved.sample_count}"
    return f"{value.mean:.{digits}f}±{value.stddev:.{digits}f}{unit} {resolved.provenance}{count}"


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
    typer.echo(f"walking_segments: {_counts(counts)}")


@app.command()
def calibrate(
    dry_run: Annotated[bool, typer.Option("--dry-run", help="계산만 하고 DB에 쓰지 않는다")] = False,
) -> None:
    """도보 실측·탑승 시도 전체로 세 보정 테이블을 다시 계산해 upsert한다 (ALGORITHM 5절)."""
    with connect() as conn:
        walks = load_walk_samples(conn)
        attempts = load_attempt_samples(conn)
        profiles, walk_skipped = walking_profiles(walks)
        predictions, prediction_skipped = prediction_calibrations(attempts)
        travel_times, travel_skipped = travel_time_calibrations(attempts)

        typer.echo(f"walking_segments {len(walks)}, boarding_attempts {len(attempts)}")
        typer.echo(
            f"user_walking_profile: groups {len(profiles)} "
            f"(per-leg {sum(p.route_leg_id is not None for p in profiles)}, "
            f"global {sum(p.route_leg_id is None for p in profiles)}), "
            f"skipped {walk_skipped.total()}{_breakdown(walk_skipped.elements())}"
        )
        typer.echo(
            f"transit_prediction_calibration: groups {len(predictions)}, "
            f"skipped {prediction_skipped.total()}{_breakdown(prediction_skipped.elements())}"
        )
        typer.echo(
            f"transit_travel_time_calibration: groups {len(travel_times)}, "
            f"skipped {travel_skipped.total()}{_breakdown(travel_skipped.elements())}"
        )
        if dry_run:
            conn.rollback()
            typer.echo("dry-run: nothing written")
            return
        results = [
            ("user_walking_profile", save_walking_profiles(conn, profiles)),
            ("transit_prediction_calibration", save_prediction_calibrations(conn, predictions)),
            ("transit_travel_time_calibration", save_travel_time_calibrations(conn, travel_times)),
        ]
    for table, counts in results:
        typer.echo(f"{table}: {_counts(counts)}")


@app.command()
def evaluate(
    date_from: Annotated[
        str | None, typer.Option("--from", help="이 target_date부터 (YYYY-MM-DD, KST, 포함). 없으면 처음부터")
    ] = None,
    date_to: Annotated[
        str | None, typer.Option("--to", help="이 target_date까지 (YYYY-MM-DD, KST, 포함). 없으면 끝까지")
    ] = None,
    dry_run: Annotated[bool, typer.Option("--dry-run", help="계산만 하고 DB에 쓰지 않는다")] = False,
) -> None:
    """그날 마지막 추천과 그날 trip을 짝지어 `recommendation_evaluations`에 upsert한다 (#72)."""
    start, end = _parse_date(date_from, "--from"), _parse_date(date_to, "--to")
    if start is not None and end is not None and start > end:
        typer.echo("--from must not be after --to", err=True)
        raise typer.Exit(2)

    with connect() as conn:
        recommendations = load_recommendations(conn, start, end)
        trips = load_trips(conn, start, end)
        legs = load_transit_legs(conn, sorted({r.commute_route_id for r in recommendations}))
        latest = latest_recommendations(recommendations)
        evaluations = evaluate_all(recommendations, trips, legs)

        window = f"{start or ''}..{end or ''}" if start or end else "all"
        typer.echo(
            f"target_date {window}: recommendations {len(recommendations)} "
            f"(latest per route·date·version {len(latest)}), trips {len(trips)}"
        )
        typer.echo(f"  evaluated {len(evaluations)}, no trip {len(latest) - len(evaluations)}")
        for summary in summarize(evaluations):
            typer.echo(f"  {_summary(summary)}")
        if dry_run:
            conn.rollback()
            typer.echo("dry-run: nothing written")
            return
        counts = save_evaluations(conn, start, end, evaluations)
    typer.echo(f"recommendation_evaluations: {_counts(counts)}")


def _summary(summary: VersionSummary) -> str:
    rate = "-" if summary.late_rate is None else f"{summary.late_rate:.0%}"
    return (
        f"{summary.model_version}: n {summary.n}, late {summary.late}/{summary.with_arrival} ({rate}), "
        f"mean departure diff {_signed_sec(summary.mean_departure_diff_sec)}, "
        f"mean stop wait {_signed_sec(summary.mean_stop_wait_sec, sign=False)}"
    )


def _signed_sec(value: float | None, sign: bool = True) -> str:
    if value is None:
        return "-"
    return f"{value:+.0f}s" if sign else f"{value:.0f}s"


def _counts(counts: UpsertCounts) -> str:
    return (
        f"created {counts.created}, updated {counts.updated}, "
        f"unchanged {counts.unchanged}, deleted {counts.deleted}"
    )


def _breakdown(values: Iterable[str]) -> str:
    counted = Counter(values)
    return f" ({', '.join(f'{key} {n}' for key, n in sorted(counted.items()))})" if counted else ""


if __name__ == "__main__":
    app()
