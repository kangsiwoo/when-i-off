package com.kangsiwoo.whenioff.signal.api

import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import java.time.Instant

data class CreateTrafficSignalRequest(
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val lat: Double,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val lng: Double,
    val stdgCd: String? = null,
    val crsrdId: String? = null,
    val name: String? = null,
    val description: String? = null,
)

data class TrafficSignalResponse(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val stdgCd: String?,
    val crsrdId: String?,
    val name: String?,
    val description: String?,
    val createdAt: Instant,
    val distanceM: Double? = null,
) {
    companion object {
        fun from(
            signal: TrafficSignal,
            distanceM: Double? = null,
        ) = TrafficSignalResponse(
            id = signal.id!!,
            lat = signal.lat,
            lng = signal.lng,
            stdgCd = signal.stdgCd,
            crsrdId = signal.crsrdId,
            name = signal.name,
            description = signal.description,
            createdAt = signal.createdAt,
            distanceM = distanceM,
        )
    }
}
