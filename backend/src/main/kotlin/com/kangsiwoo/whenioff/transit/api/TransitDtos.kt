package com.kangsiwoo.whenioff.transit.api

import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class CreateTransitLineRequest(
    val mode: TransitMode,
    @field:NotBlank val name: String,
    val stdgCd: String? = null,
    val externalId: String? = null,
    val agency: String? = null,
    val hasRealtimeApi: Boolean = true,
)

data class TransitLineResponse(
    val id: Long,
    val mode: TransitMode,
    val name: String,
    val stdgCd: String?,
    val externalId: String?,
    val agency: String?,
    val hasRealtimeApi: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(line: TransitLine) =
            TransitLineResponse(
                id = line.id!!,
                mode = line.mode,
                name = line.name,
                stdgCd = line.stdgCd,
                externalId = line.externalId,
                agency = line.agency,
                hasRealtimeApi = line.hasRealtimeApi,
                createdAt = line.createdAt,
            )
    }
}

data class CreateTransitStopRequest(
    val mode: TransitMode,
    @field:NotBlank val name: String,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val lat: Double,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val lng: Double,
    val stdgCd: String? = null,
    val externalId: String? = null,
)

data class TransitStopResponse(
    val id: Long,
    val mode: TransitMode,
    val name: String,
    val lat: Double,
    val lng: Double,
    val stdgCd: String?,
    val externalId: String?,
    val createdAt: Instant,
    val distanceM: Double? = null,
) {
    companion object {
        fun from(
            stop: TransitStop,
            distanceM: Double? = null,
        ) = TransitStopResponse(
            id = stop.id!!,
            mode = stop.mode,
            name = stop.name,
            lat = stop.lat,
            lng = stop.lng,
            stdgCd = stop.stdgCd,
            externalId = stop.externalId,
            createdAt = stop.createdAt,
            distanceM = distanceM,
        )
    }
}
