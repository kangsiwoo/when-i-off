package com.kangsiwoo.whenioff.transit.application.geo

import com.kangsiwoo.whenioff.common.geo.LatLng
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolylineProjectionTest {
    private val stops =
        listOf(
            LatLng(37.2000, 127.0700),
            LatLng(37.2030, 127.0700),
            LatLng(37.2060, 127.0710),
            LatLng(37.2060, 127.0750),
            LatLng(37.2090, 127.0760),
            LatLng(37.2120, 127.0760),
        )
    private val polyline = Polyline(stops)

    @Test
    fun `segment lengths are a few hundred metres and cumulative distance grows monotonically`() {
        polyline.segmentLengthsM.forEach { assertTrue(it in 300.0..400.0, "segment $it") }
        assertEquals(0.0, polyline.cumulativeM.first())
        assertEquals(polyline.segmentLengthsM.sum(), polyline.cumulativeM.last(), 1e-6)
    }

    @Test
    fun `vehicle midway on the first segment projects onto it`() {
        val p = PolylineProjection.project(LatLng(37.2015, 127.0700), polyline)

        assertEquals(0, p.segmentIndex)
        assertClose(polyline.segmentLengthsM[0] / 2, p.distanceAlongM, 1.0)
        assertTrue(p.offsetM < 1.0)
    }

    @Test
    fun `remaining distance to a downstream stop is cumulative minus distance along`() {
        val p = PolylineProjection.project(LatLng(37.2015, 127.0700), polyline)

        val remaining = PolylineProjection.remainingDistanceM(p, polyline, 3)

        assertNotNull(remaining)
        assertClose(polyline.cumulativeM[3] - p.distanceAlongM, remaining, 1e-6)
        assertTrue(remaining in 850.0..880.0)
    }

    @Test
    fun `vehicle past the target stop yields null remaining`() {
        val p = PolylineProjection.project(LatLng(37.2075, 127.0755), polyline)

        assertEquals(3, p.segmentIndex)
        assertNull(PolylineProjection.remainingDistanceM(p, polyline, 3))
        assertNotNull(PolylineProjection.remainingDistanceM(p, polyline, 5))
    }

    @Test
    fun `offset measures perpendicular distance from the polyline`() {
        val p = PolylineProjection.project(LatLng(37.2015, 127.0710), polyline)

        assertEquals(0, p.segmentIndex)
        assertClose(88.6, p.offsetM, 2.0)
    }

    @Test
    fun `single point polyline projects to the point itself`() {
        val single = Polyline(listOf(stops[0]))

        val p = PolylineProjection.project(LatLng(37.2015, 127.0700), single)

        assertEquals(Projection(0, 0.0, p.offsetM), p)
        assertClose(166.8, p.offsetM, 1.0)
    }

    @Test
    fun `segment bearings and heading differences`() {
        assertClose(0.0, PolylineProjection.segmentBearingDeg(polyline, 0)!!, 0.5)
        assertClose(90.0, PolylineProjection.segmentBearingDeg(polyline, 2)!!, 0.5)
        assertEquals(20.0, PolylineProjection.headingDifferenceDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, PolylineProjection.headingDifferenceDeg(0.0, 180.0), 1e-9)
    }

    @Test
    fun `eta uses the speed floor of 15 kmh`() {
        assertEquals(104, EtaCalculator.etaSeconds(866.0, 30.0))
        assertEquals(240, EtaCalculator.etaSeconds(1000.0, null))
        assertEquals(240, EtaCalculator.etaSeconds(1000.0, 5.0))
        assertEquals(0, EtaCalculator.etaSeconds(0.0, 40.0))
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
        tolerance: Double,
    ) = assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance but was $actual")
}
