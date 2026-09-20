package com.kangsiwoo.whenioff.transit.application.geo

import com.kangsiwoo.whenioff.common.geo.Geo
import com.kangsiwoo.whenioff.common.geo.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

data class Projection(
    val segmentIndex: Int,
    val distanceAlongM: Double,
    val offsetM: Double,
)

class Polyline(
    val points: List<LatLng>,
) {
    init {
        require(points.isNotEmpty()) { "polyline needs at least one point" }
    }

    val segmentLengthsM: List<Double> = points.zipWithNext { a, b -> Geo.haversineM(a, b) }

    val cumulativeM: List<Double> = segmentLengthsM.runningFold(0.0) { acc, len -> acc + len }
}

object PolylineProjection {
    fun project(
        point: LatLng,
        polyline: Polyline,
    ): Projection {
        val pts = polyline.points
        if (pts.size == 1) return Projection(0, 0.0, Geo.haversineM(point, pts[0]))

        val origin = point
        val p = toLocalM(point, origin)
        var best: Projection? = null
        for (i in 0 until pts.size - 1) {
            val a = toLocalM(pts[i], origin)
            val b = toLocalM(pts[i + 1], origin)
            val abx = b.first - a.first
            val aby = b.second - a.second
            val ab2 = abx * abx + aby * aby
            val t =
                if (ab2 == 0.0) {
                    0.0
                } else {
                    (((p.first - a.first) * abx + (p.second - a.second) * aby) / ab2).coerceIn(0.0, 1.0)
                }
            val cx = a.first + t * abx
            val cy = a.second + t * aby
            val offset = sqrt((p.first - cx) * (p.first - cx) + (p.second - cy) * (p.second - cy))
            if (best == null || offset < best.offsetM) {
                best = Projection(i, polyline.cumulativeM[i] + t * polyline.segmentLengthsM[i], offset)
            }
        }
        return best!!
    }

    fun remainingDistanceM(
        projection: Projection,
        polyline: Polyline,
        targetStopIndex: Int,
    ): Double? {
        require(targetStopIndex in polyline.points.indices) { "targetStopIndex out of range" }
        val remaining = polyline.cumulativeM[targetStopIndex] - projection.distanceAlongM
        return if (remaining < 0.0) null else remaining
    }

    fun segmentBearingDeg(
        polyline: Polyline,
        segmentIndex: Int,
    ): Double? {
        if (polyline.points.size < 2) return null
        val a = polyline.points[segmentIndex]
        val b = polyline.points[segmentIndex + 1]
        val local = toLocalM(b, a)
        val bearing = Math.toDegrees(atan2(local.first, local.second))
        return (bearing + 360.0) % 360.0
    }

    fun headingDifferenceDeg(
        a: Double,
        b: Double,
    ): Double {
        val diff = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (diff > 180.0) 360.0 - diff else diff
    }

    private fun toLocalM(
        point: LatLng,
        origin: LatLng,
    ): Pair<Double, Double> {
        val x = Math.toRadians(point.lng - origin.lng) * cos(Math.toRadians(origin.lat)) * Geo.EARTH_RADIUS_M
        val y = Math.toRadians(point.lat - origin.lat) * Geo.EARTH_RADIUS_M
        return x to y
    }
}
