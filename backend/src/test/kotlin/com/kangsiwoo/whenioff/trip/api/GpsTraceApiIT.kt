package com.kangsiwoo.whenioff.trip.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
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
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `GET /commute-trips/{id}/gps-traces` (#64): trip 상세의 GPS 트랙. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GpsTraceApiIT {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute("TRUNCATE gps_traces, commute_trips, commute_routes RESTART IDENTITY CASCADE")
        jdbcTemplate.update("DELETE FROM users WHERE id <> ?", DefaultUser.ID)
    }

    @Test
    fun `returns only this trip's points ordered by recordedAt`() {
        val routeId = insertRoute(DefaultUser.ID)
        val tripId = insertTrip(routeId, DefaultUser.ID)
        val otherTripId = insertTrip(routeId, DefaultUser.ID)

        // 업로드 순서를 섞는다: 응답은 기록 시각 순이어야 한다.
        upload(
            tripId,
            listOf(
                point("2026-09-20T22:31:00Z", 37.201, 127.071, speedMps = 1.3, accuracyM = 8.0),
                point("2026-09-20T22:30:00Z", 37.200, 127.070),
                point("2026-09-20T22:30:30Z", 37.2005, 127.0705, accuracyM = 12.5),
            ),
        )
        upload(otherTripId, listOf(point("2026-09-20T22:30:10Z", 37.3, 127.2)))
        upload(null, listOf(point("2026-09-20T22:30:20Z", 37.4, 127.3))) // 상시 수집분

        val body = traces(tripId).andExpect { status { isOk() } }.json()
        assertEquals(
            listOf("2026-09-20T22:30:00Z", "2026-09-20T22:30:30Z", "2026-09-20T22:31:00Z"),
            body.map { it["recordedAt"].asText() },
        )
        val last = body[2]
        assertEquals(37.201, last["lat"].asDouble())
        assertEquals(127.071, last["lng"].asDouble())
        assertEquals(1.3, last["speedMps"].asDouble())
        assertEquals(8.0, last["accuracyM"].asDouble())
        // 값이 없는 선택 필드는 키째로 빠진다 (non_null).
        assertFalse(body[0].has("speedMps"))
        assertFalse(body[0].has("accuracyM"))
        assertEquals(12.5, body[1]["accuracyM"].asDouble())
    }

    @Test
    fun `a trip without points gives an empty list`() {
        val tripId = insertTrip(insertRoute(DefaultUser.ID), DefaultUser.ID)
        assertEquals(0, traces(tripId).andExpect { status { isOk() } }.json().size())
    }

    @Test
    fun `unknown trip and another user's trip give 404`() {
        traces(999_999).andExpect { status { isNotFound() } }

        val otherUser =
            jdbcTemplate.queryForObject(
                "INSERT INTO users (email, display_name) VALUES ('other@when-i-off.local', 'other') RETURNING id",
                Long::class.java,
            )!!
        val otherTrip = insertTrip(insertRoute(otherUser), otherUser)
        jdbcTemplate.update(
            "INSERT INTO gps_traces (user_id, commute_trip_id, recorded_at, lat, lng) " +
                "VALUES (?, ?, '2026-09-20T22:30:00Z', 37.2, 127.07)",
            otherUser,
            otherTrip,
        )
        traces(otherTrip).andExpect { status { isNotFound() } }
    }

    @Test
    fun `requires the api token`() {
        val tripId = insertTrip(insertRoute(DefaultUser.ID), DefaultUser.ID)
        mockMvc.get("/api/v1/commute-trips/$tripId/gps-traces").andExpect { status { isUnauthorized() } }
    }

    // --- fixtures ---

    private fun point(
        recordedAt: String,
        lat: Double,
        lng: Double,
        speedMps: Double? = null,
        accuracyM: Double? = null,
    ): Map<String, Any?> =
        mapOf("recordedAt" to recordedAt, "lat" to lat, "lng" to lng, "speedMps" to speedMps, "accuracyM" to accuracyM)

    private fun upload(
        tripId: Long?,
        points: List<Map<String, Any?>>,
    ) {
        mockMvc
            .post("/api/v1/gps-traces/batch") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("tripId" to tripId, "points" to points))
            }.andExpect { status { isOk() } }
    }

    private fun traces(tripId: Long): ResultActionsDsl =
        mockMvc.get("/api/v1/commute-trips/$tripId/gps-traces") {
            header(ApiTokenFilter.HEADER, properties.apiToken)
        }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)

    private fun insertRoute(userId: Long): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_routes (user_id, name, direction, origin_lat, origin_lng, destination_lat, destination_lng)
            VALUES (?, '출근', 'TO_WORK', 37.2, 127.07, 37.49, 127.1) RETURNING id
            """.trimIndent(),
            Long::class.java,
            userId,
        )!!

    private fun insertTrip(
        routeId: Long,
        userId: Long,
    ): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO commute_trips (user_id, commute_route_id, trip_date) VALUES (?, ?, '2026-09-21') RETURNING id",
            Long::class.java,
            userId,
            routeId,
        )!!
}
