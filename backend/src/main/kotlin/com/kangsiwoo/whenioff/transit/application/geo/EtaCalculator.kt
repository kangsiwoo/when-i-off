package com.kangsiwoo.whenioff.transit.application.geo

import kotlin.math.max
import kotlin.math.roundToLong

object EtaCalculator {
    const val MIN_SPEED_KMH = 15.0

    fun etaSeconds(
        remainingM: Double,
        speedKmh: Double?,
    ): Long {
        require(remainingM >= 0.0) { "remaining distance must be non-negative" }
        val effectiveKmh = max(speedKmh ?: 0.0, MIN_SPEED_KMH)
        return (remainingM / (effectiveKmh / 3.6)).roundToLong()
    }
}
