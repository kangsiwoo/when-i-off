package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BoardingAttemptRepository : JpaRepository<BoardingAttempt, Long> {
    /** 한 구간의 시도들을 차례대로. 차를 놓치고 다음 차를 타면 여러 건이다 (#38). */
    fun findByCommuteTripIdAndRouteLegIdOrderByAttemptSeqAsc(
        commuteTripId: Long,
        routeLegId: Long,
    ): List<BoardingAttempt>

    fun findByIdAndCommuteTripUserId(
        id: Long,
        userId: Long,
    ): BoardingAttempt?

    fun findByCommuteTripIdInOrderByRouteLegSeqOrderAscAttemptSeqAsc(
        commuteTripIds: Collection<Long>,
    ): List<BoardingAttempt>
}
