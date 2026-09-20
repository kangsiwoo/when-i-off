package com.kangsiwoo.whenioff.trip.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.route.domain.CommuteDirection
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import com.kangsiwoo.whenioff.user.domain.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripApiTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var commuteRouteRepository: CommuteRouteRepository

    @Autowired lateinit var routeLegRepository: RouteLegRepository

    @Autowired lateinit var transitLineRepository: TransitLineRepository

    @Autowired lateinit var transitStopRepository: TransitStopRepository

    private lateinit var route: CommuteRoute
    private lateinit var walkLeg: RouteLeg
    private lateinit var transitLeg: RouteLeg

    @BeforeEach
    fun seedRoute() {
        jdbcTemplate.execute(
            "TRUNCATE gps_traces, boarding_attempts, commute_trips, route_legs, commute_routes, " +
                "transit_stops, transit_lines RESTART IDENTITY CASCADE",
        )
        val user = userRepository.getReferenceById(DefaultUser.ID)
        route =
            commuteRouteRepository.save(
                CommuteRoute(
                    user = user,
                    name = "출근",
                    direction = CommuteDirection.TO_WORK,
                    originLat = 37.39,
                    originLng = 127.11,
                    destinationLat = 37.50,
                    destinationLng = 127.03,
                ),
            )
        walkLeg = saveWalkLeg(route, seqOrder = 1)
        transitLeg = saveTransitLeg(route, seqOrder = 2)
        saveWalkLeg(route, seqOrder = 3)
    }

    @Test
    fun `golden scenario - trip, attempt upsert, result patch, history`() {
        val created =
            postJson(
                "/api/v1/commute-trips",
                mapOf("routeId" to route.id, "tripDate" to "2026-09-21", "leftHomeAt" to "2026-09-20T22:30:00Z"),
            ).andExpect { status { isCreated() } }.json()
        val tripId = created["id"].asLong()
        assertEquals(route.id, created["routeId"].asLong())
        assertEquals("2026-09-20T22:30:00Z", created["leftHomeAt"].asText())

        val first =
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf(
                    "routeLegId" to transitLeg.id,
                    "arrivedAtStopAt" to "2026-09-20T22:36:00Z",
                    "vehicleScheduledOrPredictedAt" to "2026-09-20T22:40:00Z",
                ),
            ).andExpect { status { isCreated() } }.json()
        val attemptId = first["id"].asLong()
        assertEquals("UNKNOWN", first["result"].asText())

        val upserted =
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf("routeLegId" to transitLeg.id, "vehicleActualDepartureAt" to "2026-09-20T22:41:30Z"),
            ).andExpect { status { isOk() } }.json()
        assertEquals(attemptId, upserted["id"].asLong())
        assertEquals("2026-09-20T22:36:00Z", upserted["arrivedAtStopAt"].asText())
        assertEquals("2026-09-20T22:41:30Z", upserted["vehicleActualDepartureAt"].asText())
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java))

        val patched =
            patchJson(
                "/api/v1/boarding-attempts/$attemptId",
                mapOf("result" to "CAUGHT", "alightedAt" to "2026-09-20T23:20:00Z"),
            ).andExpect { status { isOk() } }.json()
        assertEquals("CAUGHT", patched["result"].asText())

        patchJson("/api/v1/commute-trips/$tripId", mapOf("arrivedDestinationAt" to "2026-09-20T23:28:00Z"))
            .andExpect { status { isOk() } }

        val history =
            mockMvc
                .get("/api/v1/commute-trips") {
                    header(ApiTokenFilter.HEADER, properties.apiToken)
                    param("routeId", route.id.toString())
                    param("from", "2026-09-01")
                    param("to", "2026-09-30")
                }.andExpect { status { isOk() } }
                .json()
        assertEquals(1, history.size())
        val trip = history[0]
        assertEquals(tripId, trip["id"].asLong())
        assertEquals("2026-09-20T23:28:00Z", trip["arrivedDestinationAt"].asText())
        assertEquals(1, trip["boardingAttempts"].size())
        val embedded = trip["boardingAttempts"][0]
        assertEquals(attemptId, embedded["id"].asLong())
        assertEquals(transitLeg.id, embedded["routeLegId"].asLong())
        assertEquals("CAUGHT", embedded["result"].asText())
        assertEquals("2026-09-20T23:20:00Z", embedded["alightedAt"].asText())
    }

    @Test
    fun `history is ordered by trip date desc and filtered by range`() {
        for (date in listOf("2026-09-01", "2026-09-03", "2026-09-02")) {
            postJson("/api/v1/commute-trips", mapOf("routeId" to route.id, "tripDate" to date))
                .andExpect { status { isCreated() } }
        }
        val all = mockMvc.get("/api/v1/commute-trips") { header(ApiTokenFilter.HEADER, properties.apiToken) }.json()
        assertEquals(listOf("2026-09-03", "2026-09-02", "2026-09-01"), all.map { it["tripDate"].asText() })

        val ranged =
            mockMvc
                .get("/api/v1/commute-trips") {
                    header(ApiTokenFilter.HEADER, properties.apiToken)
                    param("from", "2026-09-02")
                }.json()
        assertEquals(listOf("2026-09-03", "2026-09-02"), ranged.map { it["tripDate"].asText() })
    }

    @Test
    fun `boarding attempt rejects WALK leg and leg of another route`() {
        val tripId = createTrip()
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to walkLeg.id))
            .andExpect { status { isBadRequest() } }

        val otherRoute =
            commuteRouteRepository.save(
                CommuteRoute(
                    user = userRepository.getReferenceById(DefaultUser.ID),
                    name = "퇴근",
                    direction = CommuteDirection.TO_HOME,
                    originLat = 0.0,
                    originLng = 0.0,
                    destinationLat = 0.0,
                    destinationLng = 0.0,
                ),
            )
        val foreignLeg = saveTransitLeg(otherRoute, seqOrder = 1)
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to foreignLeg.id))
            .andExpect { status { isBadRequest() } }
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to 999_999))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `boarding attempt creation rejects alightedAt before departure`() {
        val tripId = createTrip()
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf(
                "routeLegId" to transitLeg.id,
                "vehicleActualDepartureAt" to "2026-09-20T22:41:30Z",
                "alightedAt" to "2026-09-20T22:30:00Z",
            ),
        ).andExpect { status { isBadRequest() } }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java))
    }

    @Test
    fun `concurrent first upserts for the same leg produce one row and no 409`() {
        val tripId = createTrip()
        val body =
            objectMapper.writeValueAsString(
                mapOf("routeLegId" to transitLeg.id, "arrivedAtStopAt" to "2026-09-20T22:36:00Z"),
            )
        val workers = 4
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        val statuses =
            try {
                val futures =
                    (1..workers).map {
                        pool.submit(
                            Callable {
                                start.await()
                                mockMvc
                                    .post("/api/v1/commute-trips/$tripId/boarding-attempts") {
                                        header(ApiTokenFilter.HEADER, properties.apiToken)
                                        contentType = MediaType.APPLICATION_JSON
                                        content = body
                                    }.andReturn()
                                    .response.status
                            },
                        )
                    }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(1, statuses.count { it == 201 }, "$statuses")
        assertEquals(workers - 1, statuses.count { it == 200 }, "$statuses")
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java))
    }

    @Test
    fun `unknown trip and route give 404`() {
        postJson("/api/v1/commute-trips", mapOf("routeId" to 999_999, "tripDate" to "2026-09-21"))
            .andExpect { status { isNotFound() } }
        patchJson("/api/v1/commute-trips/999999", mapOf("arrivedDestinationAt" to "2026-09-20T23:28:00Z"))
            .andExpect { status { isNotFound() } }
        postJson("/api/v1/gps-traces/batch", mapOf("tripId" to 999_999, "points" to listOf(point(0))))
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `gps batch ignores duplicates on (user, recordedAt)`() {
        val tripId = createTrip()
        val points = (0 until 5).map(::point)

        val firstBatch =
            postJson("/api/v1/gps-traces/batch", mapOf("tripId" to tripId, "points" to points))
                .andExpect { status { isOk() } }
                .json()
        assertEquals(5, firstBatch["accepted"].asInt())
        assertEquals(0, firstBatch["ignored"].asInt())

        val resent = points.take(3) + (5 until 7).map(::point) + point(6)
        val secondBatch =
            postJson("/api/v1/gps-traces/batch", mapOf("points" to resent))
                .andExpect { status { isOk() } }
                .json()
        assertEquals(2, secondBatch["accepted"].asInt())
        assertEquals(4, secondBatch["ignored"].asInt())

        assertEquals(7, jdbcTemplate.queryForObject("SELECT count(*) FROM gps_traces", Int::class.java))
        assertEquals(
            5,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM gps_traces WHERE commute_trip_id = ?",
                Int::class.java,
                tripId,
            ),
        )
        val stored =
            jdbcTemplate.queryForMap(
                "SELECT speed_mps, accuracy_m FROM gps_traces ORDER BY recorded_at LIMIT 1",
            )
        assertEquals(1.0, stored["speed_mps"])
        assertEquals(10.0, stored["accuracy_m"])
    }

    @Test
    fun `gps batch rejects more than 500 points and empty batch`() {
        postJson("/api/v1/gps-traces/batch", mapOf("points" to (0 until 501).map(::point)))
            .andExpect { status { isBadRequest() } }
        postJson("/api/v1/gps-traces/batch", mapOf("points" to emptyList<Any>()))
            .andExpect { status { isBadRequest() } }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM gps_traces", Int::class.java))

        postJson("/api/v1/gps-traces/batch", mapOf("points" to (0 until 500).map(::point)))
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.accepted") { value(500) } }
    }

    @Test
    fun `requests without api token are rejected`() {
        mockMvc
            .post("/api/v1/commute-trips") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("routeId" to route.id, "tripDate" to "2026-09-21"))
            }.andExpect { status { isUnauthorized() } }
    }

    private fun saveWalkLeg(
        route: CommuteRoute,
        seqOrder: Int,
    ): RouteLeg =
        routeLegRepository.save(
            RouteLeg(
                commuteRoute = route,
                seqOrder = seqOrder,
                legType = LegType.WALK,
                startLat = 37.39,
                startLng = 127.11,
                endLat = 37.391,
                endLng = 127.111,
                plannedDistanceM = 400.0,
            ),
        )

    private fun saveTransitLeg(
        route: CommuteRoute,
        seqOrder: Int,
    ): RouteLeg {
        val line = transitLineRepository.save(TransitLine(mode = TransitMode.BUS, name = "M4403"))
        val boardStop = transitStopRepository.save(TransitStop(TransitMode.BUS, "집앞", 37.391, 127.111))
        val alightStop = transitStopRepository.save(TransitStop(TransitMode.BUS, "회사앞", 37.499, 127.031))
        return routeLegRepository.save(
            RouteLeg(
                commuteRoute = route,
                seqOrder = seqOrder,
                legType = LegType.TRANSIT,
                transitLine = line,
                boardStop = boardStop,
                alightStop = alightStop,
                plannedTravelSec = 2400,
            ),
        )
    }

    private fun createTrip(): Long =
        postJson("/api/v1/commute-trips", mapOf("routeId" to route.id, "tripDate" to "2026-09-21"))
            .andExpect { status { isCreated() } }
            .json()["id"]
            .asLong()

    private fun point(index: Int): Map<String, Any> =
        mapOf(
            "recordedAt" to BASE.plusSeconds(index * 10L).toString(),
            "lat" to 37.39 + index * 0.0001,
            "lng" to 127.11,
            "speedMps" to 1.0,
            "accuracyM" to 10.0,
        )

    private fun postJson(
        path: String,
        body: Any,
    ): ResultActionsDsl =
        mockMvc.post(path) {
            header(ApiTokenFilter.HEADER, properties.apiToken)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }

    private fun patchJson(
        path: String,
        body: Any,
    ): ResultActionsDsl =
        mockMvc.patch(path) {
            header(ApiTokenFilter.HEADER, properties.apiToken)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }

    private fun ResultActionsDsl.json(): JsonNode {
        val body = andReturn().response.contentAsString
        assertNotNull(body)
        return objectMapper.readTree(body)
    }

    private companion object {
        val BASE: Instant = Instant.parse("2026-09-20T22:30:00Z")
    }
}
