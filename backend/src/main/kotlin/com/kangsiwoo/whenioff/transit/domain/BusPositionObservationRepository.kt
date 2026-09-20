package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface BusPositionObservationRepository : JpaRepository<BusPositionObservation, Long> {
    fun existsByTransitLineIdAndVehicleNoAndObservedAt(
        transitLineId: Long,
        vehicleNo: String,
        observedAt: Instant,
    ): Boolean
}
