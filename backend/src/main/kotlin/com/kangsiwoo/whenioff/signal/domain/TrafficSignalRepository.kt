package com.kangsiwoo.whenioff.signal.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TrafficSignalRepository : JpaRepository<TrafficSignal, Long> {
    fun findByLatBetweenAndLngBetween(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): List<TrafficSignal>
}
