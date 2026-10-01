"""`recommend` 인자 규칙과 `--all-active-routes`의 경로별 실패 처리·종료 코드 (#68). DB는 가짜다."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime, time
from typing import Any

import pytest
from typer.testing import CliRunner

from whenioff_analytics import cli
from whenioff_analytics.daytype import KST, DayType
from whenioff_analytics.model.targets import RouteTarget
from whenioff_analytics.service import IncompleteLegError

runner = CliRunner()


class FakeConnection:
    def __init__(self) -> None:
        self.transactions = 0

    def rollback(self) -> None:
        pass

    @contextmanager
    def transaction(self) -> Iterator[None]:
        self.transactions += 1
        yield


@pytest.fixture
def fake_db(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    state: dict[str, Any] = {
        "routes": [],
        "fail": set(),
        "computed": [],
        "stored": [],
        "conn": FakeConnection(),
    }

    @contextmanager
    def connect() -> Iterator[FakeConnection]:
        yield state["conn"]

    def compute(_conn: object, route_id: int, target: datetime, *_args: object) -> tuple[int, datetime]:
        if route_id in state["fail"]:
            raise IncompleteLegError(route_id, "commute route has no legs")
        state["computed"].append((route_id, target))
        return (route_id, target)

    def store(_conn: object, result: tuple[int, datetime]) -> tuple[str, int]:
        state["stored"].append(result)
        return ("created", 100 + result[0])

    monkeypatch.setattr(cli, "connect", connect)
    monkeypatch.setattr(cli, "load_active_route_targets", lambda _conn: state["routes"])
    monkeypatch.setattr(cli, "compute_recommendation", compute)
    monkeypatch.setattr(cli, "store", store)
    monkeypatch.setattr(cli, "_report", lambda result: None)
    return state


def target(route_id: int, arrival: time | None = time(9, 0)) -> RouteTarget:
    return RouteTarget(route_id, f"r{route_id}", arrival, frozenset({DayType.WEEKDAY}))


@pytest.mark.parametrize(
    "args",
    [
        ["--all-active-routes", "--route-id", "1"],
        ["--all-active-routes", "--target-arrival-at", "2026-10-06T09:00"],
        ["--route-id", "1", "--target-arrival-at", "2026-10-06T09:00", "--only-in-window"],
        ["--route-id", "1", "--target-arrival-at", "2026-10-06T09:00", "--date", "2026-10-06"],
        ["--route-id", "1"],
        [],
        ["--all-active-routes", "--date", "10/06"],
        ["--all-active-routes", "--now", "yesterday"],
    ],
)
def test_bad_argument_combinations_exit_2(args: list[str], fake_db: dict[str, Any]) -> None:
    result = runner.invoke(cli.app, ["recommend", *args])
    assert result.exit_code == 2, result.output
    assert fake_db["computed"] == []


def test_all_active_routes_in_window_computes_and_stores_each(fake_db: dict[str, Any]) -> None:
    fake_db["routes"] = [target(1), target(2, time(8, 0)), target(3, None)]
    result = runner.invoke(
        cli.app, ["recommend", "--all-active-routes", "--only-in-window", "--now", "2026-10-06T07:30"]
    )
    assert result.exit_code == 0, result.output
    assert fake_db["computed"] == [
        (1, datetime(2026, 10, 6, 9, 0, tzinfo=KST)),
        (2, datetime(2026, 10, 6, 8, 0, tzinfo=KST)),
    ]
    assert "route 3 (r3): skipped no_target_time" in result.output
    assert "done: computed 2, failed 0, skipped 1" in result.output
    assert fake_db["conn"].transactions == 2


def test_outside_window_does_nothing_and_exits_0(fake_db: dict[str, Any]) -> None:
    fake_db["routes"] = [target(1)]
    result = runner.invoke(
        cli.app, ["recommend", "--all-active-routes", "--only-in-window", "--now", "2026-10-06T12:00"]
    )
    assert result.exit_code == 0, result.output
    assert fake_db["computed"] == []
    assert "skipped outside_window" in result.output


def test_one_failing_route_does_not_abort_the_others(fake_db: dict[str, Any]) -> None:
    fake_db["routes"] = [target(1), target(2)]
    fake_db["fail"] = {1}
    result = runner.invoke(cli.app, ["recommend", "--all-active-routes", "--now", "2026-10-06T08:00"])
    assert result.exit_code == 0, result.output
    assert [route_id for route_id, _ in fake_db["stored"]] == [2]
    assert "route 1 (r1): error: route_leg 1: commute route has no legs" in result.output
    assert "done: computed 1, failed 1, skipped 0" in result.output


def test_exit_1_only_when_every_attempted_route_failed(fake_db: dict[str, Any]) -> None:
    fake_db["routes"] = [target(1), target(2, None)]
    fake_db["fail"] = {1}
    result = runner.invoke(cli.app, ["recommend", "--all-active-routes", "--now", "2026-10-06T08:00"])
    assert result.exit_code == 1, result.output


def test_dry_run_computes_without_storing(fake_db: dict[str, Any]) -> None:
    fake_db["routes"] = [target(1)]
    result = runner.invoke(cli.app, ["recommend", "--all-active-routes", "--date", "2026-10-06", "--dry-run"])
    assert result.exit_code == 0, result.output
    assert len(fake_db["computed"]) == 1
    assert fake_db["stored"] == []
    assert "dry-run: nothing written" in result.output


def test_single_route_mode_still_works(fake_db: dict[str, Any]) -> None:
    result = runner.invoke(
        cli.app, ["recommend", "--route-id", "7", "--target-arrival-at", "2026-10-06T09:00:00"]
    )
    assert result.exit_code == 0, result.output
    assert fake_db["computed"] == [(7, datetime(2026, 10, 6, 9, 0, tzinfo=KST))]
    assert "departure_recommendations id=107 (created)" in result.output
