"""콜드스타트 기본값 (ALGORITHM.md 5절, 2.2).

캘리브레이션 테이블(`user_walking_profile`, `transit_prediction_calibration`,
`transit_travel_time_calibration`)은 실측 기록이 0건이라 전부 비어 있다. #6의 `calibrate` 배치가
그 테이블을 채우면 여기 값들이 조회값의 fallback으로 내려앉는다 — 값을 쓰는 쪽
(`service.py`)에 조회를 한 겹 끼우면 되고, 계산 로직은 그대로다.
"""

from __future__ import annotations

from whenioff_analytics.model.distributions import Normal, SignalCycle

MODEL_VERSION = "v1"

DEFAULT_PROBABILITY = 0.95
DEFAULT_LOOKBACK_HOURS = 6

WALKING_SPEED = Normal(mean=1.2, stddev=0.15)
"""도보 속도 1.2 m/s ± 0.15 (`user_walking_profile` 대용)."""

PREDICTION_BIAS_SEC = 0.0
PREDICTION_STDDEV_SEC = 90.0
"""도착예측(GTX는 시간표) 오차 (`transit_prediction_calibration` 대용)."""

TRAVEL_TIME_CV = 0.15
"""차내 이동시간의 표준편차 = 평균의 15% (`transit_travel_time_calibration` 대용)."""

DEFAULT_SIGNAL_CYCLE = SignalCycle(cycle_sec=120.0, red_sec=90.0)
"""`traffic_signal_cycles`에 행이 없는 교차로의 DEFAULT_ASSUMPTION (평균 대기 약 34초)."""
