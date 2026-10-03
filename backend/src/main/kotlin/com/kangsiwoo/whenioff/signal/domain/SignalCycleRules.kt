package com.kangsiwoo.whenioff.signal.domain

/**
 * 신호 주기 행의 검증 규칙 (#78). DB CHECK(`0 < red < cycle`)보다 좁다 — 주기 범위와 시간대 규칙은 앱 규칙이다.
 * desktop `src/signals/cycleRules.ts`가 같은 규칙을 옮겨 쓴다. 여기를 바꾸면 그쪽도 바꾼다.
 */
object SignalCycleRules {
    /** 신호 주기의 합리적 범위(초). 도심 교차로 주기는 보통 1~3분이라 그 바깥을 넉넉히 감싼다 — 오타(초 대신 분 등)를 막는 용도. */
    const val MIN_CYCLE_SEC = 30
    const val MAX_CYCLE_SEC = 300

    /**
     * 시간대가 겹치는 행이 여럿일 때 쓸 출처의 우선순위(앞이 높다). analytics `io/signals.py`의 `SOURCE_PRIORITY`와 같다.
     */
    val SOURCE_PRIORITY: List<SignalDataSource> =
        listOf(SignalDataSource.USER_OBSERVED, SignalDataSource.PUBLIC_API, SignalDataSource.DEFAULT_ASSUMPTION)
}
