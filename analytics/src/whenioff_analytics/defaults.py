"""콜드스타트 기본값 (ALGORITHM.md 5절, 2.2).

recommend는 보정 테이블(`user_walking_profile`, `transit_prediction_calibration`,
`transit_travel_time_calibration`)을 먼저 읽고, 샘플이 부족하면(`model/lookup.py`의
`MIN_CALIBRATION_SAMPLES`) 상위 그룹을 거쳐 마지막에 여기 값으로 내려온다. calibrate도 샘플 1개
그룹의 σ로 이 값들을 쓴다.
"""

from __future__ import annotations

from whenioff_analytics.model.distributions import Normal, SignalCycle

MODEL_VERSION = "v2"
"""v1: 콜드스타트 기본값만. v2: 보정 테이블 조회 + 상속 + 기본값 fallback (#46)."""

DEFAULT_PROBABILITY = 0.95
DEFAULT_LOOKBACK_HOURS = 6

WALKING_SPEED = Normal(mean=1.2, stddev=0.15)
"""도보 속도 1.2 m/s ± 0.15 (`user_walking_profile`에 쓸 만한 행이 없을 때)."""

PREDICTION_BIAS_SEC = 0.0
PREDICTION_STDDEV_SEC = 90.0
"""도착예측(GTX는 시간표) 오차 (`transit_prediction_calibration`에 쓸 만한 행이 없을 때)."""

TRAVEL_TIME_CV = 0.15
"""차내 이동시간의 표준편차 = 평균(`planned_travel_sec`)의 15% (`transit_travel_time_calibration` 대용)."""

DEFAULT_SIGNAL_CYCLE = SignalCycle(cycle_sec=120.0, red_sec=90.0)
"""`traffic_signal_cycles`에 행이 없는 교차로의 DEFAULT_ASSUMPTION (평균 대기 약 34초)."""
