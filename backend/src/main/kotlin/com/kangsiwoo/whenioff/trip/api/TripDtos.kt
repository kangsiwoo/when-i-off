package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.trip.domain.BoardingAttempt
import com.kangsiwoo.whenioff.trip.domain.BoardingResult
import com.kangsiwoo.whenioff.trip.domain.CommuteTrip
import com.kangsiwoo.whenioff.trip.domain.GpsPoint
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

data class CreateCommuteTripRequest(
    val routeId: Long,
    val tripDate: LocalDate,
    val leftHomeAt: Instant? = null,
)

data class UpdateCommuteTripRequest(
    val leftHomeAt: Instant? = null,
    val arrivedDestinationAt: Instant? = null,
)

data class CommuteTripResponse(
    val id: Long,
    val routeId: Long,
    val tripDate: LocalDate,
    val leftHomeAt: Instant?,
    val arrivedDestinationAt: Instant?,
    val createdAt: Instant,
    val boardingAttempts: List<BoardingAttemptResponse>,
) {
    companion object {
        fun from(
            trip: CommuteTrip,
            attempts: List<BoardingAttempt>,
        ) = CommuteTripResponse(
            id = trip.id!!,
            routeId = trip.commuteRoute.id!!,
            tripDate = trip.tripDate,
            leftHomeAt = trip.leftHomeAt,
            arrivedDestinationAt = trip.arrivedDestinationAt,
            createdAt = trip.createdAt,
            boardingAttempts = attempts.map(BoardingAttemptResponse::from),
        )
    }
}

interface BoardingAttemptChanges {
    val arrivedAtStopAt: Instant?
    val vehicleScheduledOrPredictedAt: Instant?
    val vehicleActualDepartureAt: Instant?
    val alightedAt: Instant?
    val result: BoardingResult?
    val notes: String?
}

data class UpsertBoardingAttemptRequest(
    val routeLegId: Long,
    override val arrivedAtStopAt: Instant? = null,
    override val vehicleScheduledOrPredictedAt: Instant? = null,
    override val vehicleActualDepartureAt: Instant? = null,
    override val alightedAt: Instant? = null,
    override val result: BoardingResult? = null,
    @field:Size(max = 2000)
    override val notes: String? = null,
) : BoardingAttemptChanges

data class UpdateBoardingAttemptRequest(
    override val arrivedAtStopAt: Instant? = null,
    override val vehicleScheduledOrPredictedAt: Instant? = null,
    override val vehicleActualDepartureAt: Instant? = null,
    override val alightedAt: Instant? = null,
    override val result: BoardingResult? = null,
    @field:Size(max = 2000)
    override val notes: String? = null,
) : BoardingAttemptChanges

data class BoardingAttemptResponse(
    val id: Long,
    val tripId: Long,
    val routeLegId: Long,
    val arrivedAtStopAt: Instant?,
    val vehicleScheduledOrPredictedAt: Instant?,
    val vehicleActualDepartureAt: Instant?,
    val alightedAt: Instant?,
    val result: BoardingResult,
    val notes: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(attempt: BoardingAttempt) =
            BoardingAttemptResponse(
                id = attempt.id!!,
                tripId = attempt.commuteTrip.id!!,
                routeLegId = attempt.routeLeg.id!!,
                arrivedAtStopAt = attempt.arrivedAtStopAt,
                vehicleScheduledOrPredictedAt = attempt.vehicleScheduledOrPredictedAt,
                vehicleActualDepartureAt = attempt.vehicleActualDepartureAt,
                alightedAt = attempt.alightedAt,
                result = attempt.result,
                notes = attempt.notes,
                createdAt = attempt.createdAt,
            )
    }
}

data class GpsTraceBatchRequest(
    val tripId: Long? = null,
    @field:NotEmpty
    @field:Size(max = MAX_POINTS)
    @field:Valid
    val points: List<GpsPointRequest>,
) {
    companion object {
        const val MAX_POINTS = 500
    }
}

data class GpsPointRequest(
    val recordedAt: Instant,
    @field:DecimalMin("-90.0")
    @field:DecimalMax("90.0")
    val lat: Double,
    @field:DecimalMin("-180.0")
    @field:DecimalMax("180.0")
    val lng: Double,
    @field:PositiveOrZero
    val speedMps: Double? = null,
    @field:PositiveOrZero
    val accuracyM: Double? = null,
) {
    fun toPoint() = GpsPoint(recordedAt, lat, lng, speedMps, accuracyM)
}

data class GpsTraceBatchResponse(
    val accepted: Int,
    val ignored: Int,
)
