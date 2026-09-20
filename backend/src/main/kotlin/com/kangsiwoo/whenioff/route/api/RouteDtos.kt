package com.kangsiwoo.whenioff.route.api

import com.kangsiwoo.whenioff.route.domain.CommuteDirection
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossing
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import java.time.Instant

data class CreateCommuteRouteRequest(
    @field:NotBlank val name: String,
    val direction: CommuteDirection,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val originLat: Double,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val originLng: Double,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val destinationLat: Double,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val destinationLng: Double,
    val isActive: Boolean = true,
)

data class CommuteRouteResponse(
    val id: Long,
    val name: String,
    val direction: CommuteDirection,
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val isActive: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(route: CommuteRoute) =
            CommuteRouteResponse(
                id = route.id!!,
                name = route.name,
                direction = route.direction,
                originLat = route.originLat,
                originLng = route.originLng,
                destinationLat = route.destinationLat,
                destinationLng = route.destinationLng,
                isActive = route.isActive,
                createdAt = route.createdAt,
            )
    }
}

data class CommuteRouteDetailResponse(
    val route: CommuteRouteResponse,
    val legs: List<RouteLegResponse>,
)

data class RouteLegRequest(
    val id: Long? = null,
    @field:Positive val seqOrder: Int,
    val legType: LegType,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val startLat: Double? = null,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val startLng: Double? = null,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val endLat: Double? = null,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val endLng: Double? = null,
    @field:Positive val plannedDistanceM: Double? = null,
    val transitLineId: Long? = null,
    val boardStopId: Long? = null,
    val alightStopId: Long? = null,
    @field:Positive val plannedTravelSec: Int? = null,
)

data class ReplaceRouteLegsRequest(
    @field:NotEmpty @field:Valid val legs: List<RouteLegRequest>,
)

data class RouteLegResponse(
    val id: Long,
    val seqOrder: Int,
    val legType: LegType,
    val startLat: Double?,
    val startLng: Double?,
    val endLat: Double?,
    val endLng: Double?,
    val plannedDistanceM: Double?,
    val transitLineId: Long?,
    val boardStopId: Long?,
    val alightStopId: Long?,
    val plannedTravelSec: Int?,
    val signalCrossings: List<SignalCrossingResponse>,
) {
    companion object {
        fun from(
            leg: RouteLeg,
            crossings: List<RouteLegSignalCrossing>,
        ) = RouteLegResponse(
            id = leg.id!!,
            seqOrder = leg.seqOrder,
            legType = leg.legType,
            startLat = leg.startLat,
            startLng = leg.startLng,
            endLat = leg.endLat,
            endLng = leg.endLng,
            plannedDistanceM = leg.plannedDistanceM,
            transitLineId = leg.transitLine?.id,
            boardStopId = leg.boardStop?.id,
            alightStopId = leg.alightStop?.id,
            plannedTravelSec = leg.plannedTravelSec,
            signalCrossings = crossings.map(SignalCrossingResponse::from),
        )
    }
}

data class SignalCrossingRequest(
    val trafficSignalId: Long,
    @field:Positive val seqOrder: Int,
    @field:NotBlank val approachDir: String,
    @field:NotBlank val signalKind: String = "Pd",
)

data class ReplaceSignalCrossingsRequest(
    @field:Valid val crossings: List<SignalCrossingRequest>,
)

data class SignalCrossingResponse(
    val id: Long,
    val trafficSignalId: Long,
    val seqOrder: Int,
    val approachDir: String,
    val signalKind: String,
) {
    companion object {
        fun from(crossing: RouteLegSignalCrossing) =
            SignalCrossingResponse(
                id = crossing.id!!,
                trafficSignalId = crossing.trafficSignal.id!!,
                seqOrder = crossing.seqOrder,
                approachDir = crossing.approachDir,
                signalKind = crossing.signalKind,
            )
    }
}
