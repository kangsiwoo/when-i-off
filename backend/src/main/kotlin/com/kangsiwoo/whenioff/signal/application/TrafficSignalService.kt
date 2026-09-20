package com.kangsiwoo.whenioff.signal.application

import com.kangsiwoo.whenioff.common.geo.LatLng
import com.kangsiwoo.whenioff.common.geo.NearbyQuery
import com.kangsiwoo.whenioff.signal.api.CreateTrafficSignalRequest
import com.kangsiwoo.whenioff.signal.api.TrafficSignalResponse
import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class TrafficSignalService(
    private val trafficSignalRepository: TrafficSignalRepository,
) {
    fun create(request: CreateTrafficSignalRequest): TrafficSignalResponse =
        TrafficSignalResponse.from(
            trafficSignalRepository.save(
                TrafficSignal(
                    lat = request.lat,
                    lng = request.lng,
                    stdgCd = request.stdgCd?.trim()?.ifBlank { null },
                    crsrdId = request.crsrdId?.trim()?.ifBlank { null },
                    name = request.name?.trim()?.ifBlank { null },
                    description = request.description?.trim()?.ifBlank { null },
                ),
            ),
        )

    @Transactional(readOnly = true)
    fun nearby(
        lat: Double,
        lng: Double,
        radiusM: Double?,
    ): List<TrafficSignalResponse> {
        val query = NearbyQuery.of(lat, lng, radiusM)
        val box = query.boundingBox
        val candidates =
            trafficSignalRepository.findByLatBetweenAndLngBetween(box.minLat, box.maxLat, box.minLng, box.maxLng)
        return query
            .within(candidates) { LatLng(it.lat, it.lng) }
            .map { TrafficSignalResponse.from(it.item, it.distanceM) }
    }
}
