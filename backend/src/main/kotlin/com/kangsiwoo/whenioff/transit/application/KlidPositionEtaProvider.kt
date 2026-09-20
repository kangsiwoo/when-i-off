package com.kangsiwoo.whenioff.transit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.geo.LatLng
import com.kangsiwoo.whenioff.external.klid.bus.BusVehiclePosition
import com.kangsiwoo.whenioff.transit.application.geo.EtaCalculator
import com.kangsiwoo.whenioff.transit.application.geo.Polyline
import com.kangsiwoo.whenioff.transit.application.geo.PolylineProjection
import com.kangsiwoo.whenioff.transit.domain.BusPositionObservation
import com.kangsiwoo.whenioff.transit.domain.BusPositionObservationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservation
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineStopRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

@Service
class KlidPositionEtaProvider(
    private val snapshotCache: BusPositionSnapshotCache,
    private val lineStopRepository: TransitLineStopRepository,
    private val positionRepository: BusPositionObservationRepository,
    private val arrivalRepository: TransitArrivalObservationRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : ArrivalPredictionProvider {
    @Transactional
    override fun predict(
        line: TransitLine,
        boardStop: TransitStop,
        directionCode: String,
    ): List<Prediction> {
        val lineId = line.id ?: return emptyList()
        val stdgCd = line.stdgCd
        val rteId = line.externalId
        if (line.mode != TransitMode.BUS || !line.hasRealtimeApi || stdgCd == null || rteId == null) return emptyList()

        val lineStops = lineStopRepository.findPolyline(lineId, directionCode)
        val targetIndex = lineStops.indexOfFirst { it.stop.id == boardStop.id }
        if (targetIndex < 0) {
            log.warn { "board stop ${boardStop.id} not on line $lineId direction $directionCode" }
            return emptyList()
        }
        val polyline = Polyline(lineStops.map { LatLng(it.stop.lat, it.stop.lng) })
        val vehicles = snapshotCache.get(stdgCd).vehicles.filter { it.rteId == rteId }
        val computedAt = clock.instant()

        return vehicles.mapNotNull { vehicle ->
            val lat = vehicle.latitude
            val lng = vehicle.longitude
            val observedAt = vehicle.observedAt
            if (lat == null || lng == null || observedAt == null) return@mapNotNull null
            persistPosition(line, vehicle, lat, lng, observedAt)

            val projection = PolylineProjection.project(LatLng(lat, lng), polyline)
            if (projection.offsetM > MAX_OFFSET_M) return@mapNotNull null
            if (isHeadingOpposite(vehicle, polyline, projection.segmentIndex)) return@mapNotNull null
            val remaining =
                PolylineProjection.remainingDistanceM(projection, polyline, targetIndex) ?: return@mapNotNull null

            val eta = EtaCalculator.etaSeconds(remaining, vehicle.speedKmh)
            val prediction = Prediction(vehicle.vhclNo, observedAt.plusSeconds(eta), computedAt)
            arrivalRepository.save(
                TransitArrivalObservation(
                    transitLine = line,
                    stop = boardStop,
                    observedAt = prediction.observedAt,
                    predictedArrivalAt = prediction.predictedArrivalAt,
                    source = SOURCE,
                    vehicleNo = prediction.vehicleNo,
                ),
            )
            prediction
        }
    }

    private fun persistPosition(
        line: TransitLine,
        vehicle: BusVehiclePosition,
        lat: Double,
        lng: Double,
        observedAt: Instant,
    ) {
        if (positionRepository.existsByTransitLineIdAndVehicleNoAndObservedAt(
                line.id!!,
                vehicle.vhclNo,
                observedAt,
            )
        ) {
            return
        }
        positionRepository.save(
            BusPositionObservation(
                transitLine = line,
                vehicleNo = vehicle.vhclNo,
                observedAt = observedAt,
                lat = lat,
                lng = lng,
                raw = objectMapper.writeValueAsString(vehicle.raw),
                speedKmh = vehicle.speedKmh,
                headingDeg = vehicle.headingDeg,
                receiveType = vehicle.evtType.ifBlank { null },
            ),
        )
    }

    private fun isHeadingOpposite(
        vehicle: BusVehiclePosition,
        polyline: Polyline,
        segmentIndex: Int,
    ): Boolean {
        val heading = vehicle.headingDeg ?: return false
        val bearing = PolylineProjection.segmentBearingDeg(polyline, segmentIndex) ?: return false
        return PolylineProjection.headingDifferenceDeg(heading, bearing) > MAX_HEADING_DIFF_DEG
    }

    companion object {
        const val SOURCE = "KLID_RTM_LOC_ETA"
        const val MAX_OFFSET_M = 300.0
        const val MAX_HEADING_DIFF_DEG = 100.0
    }
}
