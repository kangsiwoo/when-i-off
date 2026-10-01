package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.trip.api.GpsTraceBatchRequest
import com.kangsiwoo.whenioff.trip.api.GpsTraceBatchResponse
import com.kangsiwoo.whenioff.trip.api.GpsTraceResponse
import com.kangsiwoo.whenioff.trip.domain.CommuteTripRepository
import com.kangsiwoo.whenioff.trip.domain.GpsTraceRepository
import com.kangsiwoo.whenioff.trip.infra.GpsTraceJdbcRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class GpsTraceService(
    private val commuteTripRepository: CommuteTripRepository,
    private val gpsTraceJdbcRepository: GpsTraceJdbcRepository,
    private val gpsTraceRepository: GpsTraceRepository,
) {
    fun saveBatch(
        userId: Long,
        request: GpsTraceBatchRequest,
    ): GpsTraceBatchResponse {
        request.tripId?.let { tripId ->
            commuteTripRepository.findByIdAndUserId(tripId, userId)
                ?: throw NotFoundException("commute trip $tripId not found")
        }
        val accepted =
            gpsTraceJdbcRepository.insertIgnoringDuplicates(
                userId = userId,
                commuteTripId = request.tripId,
                points = request.points.map { it.toPoint() },
            )
        return GpsTraceBatchResponse(accepted = accepted, ignored = request.points.size - accepted)
    }

    @Transactional(readOnly = true)
    fun tripTraces(
        userId: Long,
        tripId: Long,
    ): List<GpsTraceResponse> {
        commuteTripRepository.findByIdAndUserId(tripId, userId)
            ?: throw NotFoundException("commute trip $tripId not found")
        return gpsTraceRepository
            .findByCommuteTripIdOrderByRecordedAtAscIdAsc(tripId)
            .map(GpsTraceResponse::from)
    }
}
