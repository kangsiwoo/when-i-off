package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TransitPredictionCalibrationRepository : JpaRepository<TransitPredictionCalibration, Long> {
    /** (노선, 승차 정류장)의 모든 day_type × 밴드 행 — analytics `lookup.py`가 읽는 단위와 같다. */
    fun findByTransitLineIdAndStopId(
        transitLineId: Long,
        stopId: Long,
    ): List<TransitPredictionCalibration>
}
