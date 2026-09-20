package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.geo.LatLng
import com.kangsiwoo.whenioff.common.geo.NearbyQuery
import com.kangsiwoo.whenioff.transit.api.CreateTransitLineRequest
import com.kangsiwoo.whenioff.transit.api.CreateTransitStopRequest
import com.kangsiwoo.whenioff.transit.api.TransitLineResponse
import com.kangsiwoo.whenioff.transit.api.TransitStopResponse
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class TransitMasterService(
    private val transitLineRepository: TransitLineRepository,
    private val transitStopRepository: TransitStopRepository,
) {
    fun createLine(request: CreateTransitLineRequest): TransitLineResponse =
        TransitLineResponse.from(
            transitLineRepository.save(
                TransitLine(
                    mode = request.mode,
                    name = request.name.trim(),
                    stdgCd = request.stdgCd?.trim()?.ifBlank { null },
                    externalId = request.externalId?.trim()?.ifBlank { null },
                    agency = request.agency?.trim()?.ifBlank { null },
                    hasRealtimeApi = request.hasRealtimeApi,
                ),
            ),
        )

    @Transactional(readOnly = true)
    fun searchLines(
        mode: TransitMode?,
        keyword: String,
    ): List<TransitLineResponse> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) throw BadRequestException("keyword must not be blank")
        val page = PageRequest.of(0, SEARCH_LIMIT)
        val lines =
            if (mode == null) {
                transitLineRepository.search(trimmed, page)
            } else {
                transitLineRepository.searchByMode(mode, trimmed, page)
            }
        return lines.map(TransitLineResponse::from)
    }

    fun createStop(request: CreateTransitStopRequest): TransitStopResponse =
        TransitStopResponse.from(
            transitStopRepository.save(
                TransitStop(
                    mode = request.mode,
                    name = request.name.trim(),
                    lat = request.lat,
                    lng = request.lng,
                    stdgCd = request.stdgCd?.trim()?.ifBlank { null },
                    externalId = request.externalId?.trim()?.ifBlank { null },
                ),
            ),
        )

    @Transactional(readOnly = true)
    fun nearbyStops(
        lat: Double,
        lng: Double,
        radiusM: Double?,
        mode: TransitMode?,
    ): List<TransitStopResponse> {
        val query = NearbyQuery.of(lat, lng, radiusM)
        val box = query.boundingBox
        val candidates =
            if (mode == null) {
                transitStopRepository.findByLatBetweenAndLngBetween(box.minLat, box.maxLat, box.minLng, box.maxLng)
            } else {
                transitStopRepository.findByModeAndLatBetweenAndLngBetween(
                    mode,
                    box.minLat,
                    box.maxLat,
                    box.minLng,
                    box.maxLng,
                )
            }
        return query
            .within(candidates) { LatLng(it.lat, it.lng) }
            .map { TransitStopResponse.from(it.item, it.distanceM) }
    }

    companion object {
        private const val SEARCH_LIMIT = 50
    }
}
