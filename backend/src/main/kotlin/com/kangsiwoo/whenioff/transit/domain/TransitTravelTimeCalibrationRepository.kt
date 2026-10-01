package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TransitTravelTimeCalibrationRepository : JpaRepository<TransitTravelTimeCalibration, Long> {
    /** (노선, 승차역, 하차역)의 모든 day_type × 밴드 행. */
    fun findByTransitLineIdAndBoardStopIdAndAlightStopId(
        transitLineId: Long,
        boardStopId: Long,
        alightStopId: Long,
    ): List<TransitTravelTimeCalibration>
}
