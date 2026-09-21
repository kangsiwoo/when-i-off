"""analytics의 공휴일 복사본이 backend 원본과 어긋나지 않는지 확인한다.

모노레포 체크아웃에서만 의미가 있다 (analytics만 따로 패키징되면 backend 트리가 없다).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from whenioff_analytics.daytype import korean_holidays, parse_holidays

BACKEND_HOLIDAYS = Path(__file__).resolve().parents[2] / "backend/src/main/resources/calendar/kr-holidays.txt"


def test_analytics_copy_matches_backend_source() -> None:
    if not BACKEND_HOLIDAYS.exists():
        pytest.skip("backend tree not present (analytics packaged on its own)")
    assert parse_holidays(BACKEND_HOLIDAYS.read_text(encoding="utf-8")) == korean_holidays()
