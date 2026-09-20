package com.kangsiwoo.whenioff.common.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(
    val lat: Double,
    val lng: Double,
)

data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
)

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineM(
        a: LatLng,
        b: LatLng,
    ): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun boundingBox(
        center: LatLng,
        radiusM: Double,
    ): BoundingBox {
        val dLat = Math.toDegrees(radiusM / EARTH_RADIUS_M)
        val dLng = Math.toDegrees(radiusM / (EARTH_RADIUS_M * cos(Math.toRadians(center.lat))))
        return BoundingBox(center.lat - dLat, center.lat + dLat, center.lng - dLng, center.lng + dLng)
    }
}
