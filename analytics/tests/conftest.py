from __future__ import annotations

from datetime import datetime

from whenioff_analytics.daytype import KST


def kst(raw: str) -> datetime:
    """'2026-09-22 09:00' 같은 KST 벽시계 문자열 → 절대 시각."""
    return datetime.fromisoformat(raw).replace(tzinfo=KST)
