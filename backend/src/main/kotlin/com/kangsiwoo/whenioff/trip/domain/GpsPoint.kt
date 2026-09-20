package com.kangsiwoo.whenioff.trip.domain

import java.time.Instant

data class GpsPoint(
    val recordedAt: Instant,
    val lat: Double,
    val lng: Double,
    val speedMps: Double?,
    val accuracyM: Double?,
)
