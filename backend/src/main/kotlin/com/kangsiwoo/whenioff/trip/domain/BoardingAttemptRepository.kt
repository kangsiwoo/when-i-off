package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository

interface BoardingAttemptRepository : JpaRepository<BoardingAttempt, Long> {
    fun findByCommuteTripIdAndRouteLegId(
        commuteTripId: Long,
        routeLegId: Long,
    ): BoardingAttempt?

    fun findByIdAndCommuteTripUserId(
        id: Long,
        userId: Long,
    ): BoardingAttempt?

    fun findByCommuteTripIdInOrderByRouteLegSeqOrderAsc(commuteTripIds: Collection<Long>): List<BoardingAttempt>
}
