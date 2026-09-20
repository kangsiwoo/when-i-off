package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TransitStopRepository : JpaRepository<TransitStop, Long> {
    fun findByLatBetweenAndLngBetween(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): List<TransitStop>

    fun findByModeAndLatBetweenAndLngBetween(
        mode: TransitMode,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): List<TransitStop>

    fun findAllByModeAndStdgCd(
        mode: TransitMode,
        stdgCd: String,
    ): List<TransitStop>
}
