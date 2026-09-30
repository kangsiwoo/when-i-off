package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.Instant

interface CommuteTripRepository :
    JpaRepository<CommuteTrip, Long>,
    JpaSpecificationExecutor<CommuteTrip> {
    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): CommuteTrip?

    /** 재전송 판정용. `uq_commute_trips_route_left_home`(V4)과 같은 키다. */
    fun findByCommuteRouteIdAndLeftHomeAt(
        commuteRouteId: Long,
        leftHomeAt: Instant,
    ): CommuteTrip?
}
