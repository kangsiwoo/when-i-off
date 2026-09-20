package com.kangsiwoo.whenioff.common.geo

import com.kangsiwoo.whenioff.common.api.BadRequestException

data class Nearby<T>(
    val item: T,
    val distanceM: Double,
)

data class NearbyQuery(
    val center: LatLng,
    val radiusM: Double,
) {
    val boundingBox: BoundingBox get() = Geo.boundingBox(center, radiusM)

    companion object {
        const val DEFAULT_RADIUS_M = 500.0
        const val MAX_RADIUS_M = 5000.0

        fun of(
            lat: Double,
            lng: Double,
            radiusM: Double?,
        ): NearbyQuery {
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) throw BadRequestException("lat/lng out of range")
            val radius = radiusM ?: DEFAULT_RADIUS_M
            if (radius <= 0 || radius > MAX_RADIUS_M) {
                throw BadRequestException("radiusM must be in (0, ${MAX_RADIUS_M.toInt()}]")
            }
            return NearbyQuery(LatLng(lat, lng), radius)
        }
    }

    fun <T> within(
        candidates: Iterable<T>,
        position: (T) -> LatLng,
    ): List<Nearby<T>> =
        candidates
            .map { Nearby(it, Geo.haversineM(center, position(it))) }
            .filter { it.distanceM <= radiusM }
            .sortedBy { it.distanceM }
}
