package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface CommuteTripRepository :
    JpaRepository<CommuteTrip, Long>,
    JpaSpecificationExecutor<CommuteTrip> {
    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): CommuteTrip?
}
