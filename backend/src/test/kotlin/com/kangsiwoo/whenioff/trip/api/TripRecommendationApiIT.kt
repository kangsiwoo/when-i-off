package com.kangsiwoo.whenioff.trip.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * trip 응답의 `recommendation` (승차권, #98). 추천은 analytics가 쓰는 테이블이라 SQL로 넣는다.
 * 고르는 규칙: (경로, `trip_date`)에서 버전마다 마지막 계산 → 그 중 가장 늦게 계산된 것(같으면 뒤 버전).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripRecommendationApiIT {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private val statistics get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute(
            "TRUNCATE recommendation_evaluations, departure_recommendations, boarding_attempts, commute_trips, " +
                "route_legs, commute_routes RESTART IDENTITY CASCADE",
        )
        jdbcTemplate.update("DELETE FROM users WHERE id <> ?", DefaultUser.ID)
    }

    @AfterEach
    fun stopStatistics() {
        statistics.isStatisticsEnabled = false
    }

    @Test
    fun `each trip gets the latest computed of the per-version last computations for its route and date`() {
        val routeId = insertRoute()
        val otherRouteId = insertRoute()
        // 9/21: v1을 두 번 계산(마지막 21:00), v2는 20:00 한 번 → 버전별 마지막 중 가장 늦은 v1 21:00
        insertRecommendation(
            routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:20:00Z",
            "v1",
            "2026-09-20T18:00:00Z",
        )
        insertRecommendation(
            routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:26:00Z",
            "v1",
            "2026-09-20T21:00:00Z",
            0.95,
        )
        insertRecommendation(
            routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:30:00Z",
            "v2",
            "2026-09-20T20:00:00Z",
        )
        // 9/22: 같은 시각에 계산된 v2·v10 → 뒤 버전 v10 (문자열 순이면 v2가 이긴다). 목표 시각도 추천의 것이 나간다.
        insertRecommendation(
            routeId,
            "2026-09-22",
            "2026-09-22T00:30:00Z",
            "2026-09-21T22:40:00Z",
            "v10",
            "2026-09-21T21:00:00Z",
        )
        insertRecommendation(
            routeId,
            "2026-09-22",
            "2026-09-22T00:00:00Z",
            "2026-09-21T22:10:00Z",
            "v2",
            "2026-09-21T21:00:00Z",
        )
        // 다른 경로의 같은 날 추천은 섞이지 않는다
        insertRecommendation(
            otherRouteId,
            "2026-09-23",
            "2026-09-23T00:00:00Z",
            "2026-09-22T22:00:00Z",
            "v1",
            "2026-09-22T21:00:00Z",
        )

        val a = insertTrip(routeId, "2026-09-21", "2026-09-20T22:27:10Z")
        val b = insertTrip(routeId, "2026-09-21", "2026-09-21T09:30:00Z") // 같은 날 두 번째 trip도 같은 추천
        val c = insertTrip(routeId, "2026-09-22", "2026-09-21T22:41:00Z")
        val d = insertTrip(routeId, "2026-09-23", "2026-09-22T22:05:00Z") // 그날 이 경로 추천 없음

        val trips = search("routeId=$routeId").andExpect { status { isOk() } }.json().associateBy { it["id"].asLong() }

        for (id in listOf(a, b)) {
            val rec = trips.getValue(id)["recommendation"]
            assertEquals("v1", rec["modelVersion"].asText())
            assertEquals("2026-09-20T22:26:00Z", rec["recommendedLeaveHomeAt"].asText())
            assertEquals("2026-09-21T00:00:00Z", rec["targetArrivalAt"].asText())
            assertEquals(0.95, rec["catchProbability"].asDouble())
            assertEquals("2026-09-20T21:00:00Z", rec["computedAt"].asText())
        }
        val rc = trips.getValue(c)["recommendation"]
        assertEquals("v10", rc["modelVersion"].asText())
        assertEquals("2026-09-22T00:30:00Z", rc["targetArrivalAt"].asText())
        // 추천이 없으면 키가 없다 (null이 아니라)
        assertFalse(trips.getValue(d).has("recommendation"))
    }

    @Test
    fun `the list reads recommendations in one query however many trips there are`() {
        val routeId = insertRoute()
        val days = (1..6).map { "2026-09-%02d".format(it) }
        for (day in days) {
            insertRecommendation(routeId, day, "${day}T00:00:00Z", "${day}T22:00:00Z", "v1", "${day}T20:00:00Z")
            insertRecommendation(routeId, day, "${day}T00:00:00Z", "${day}T22:05:00Z", "v2", "${day}T20:30:00Z")
        }
        insertTrip(routeId, days[0], "${days[0]}T22:10:00Z")
        val oneTrip = statementsFor("routeId=$routeId", expectedTrips = 1)
        days.drop(1).forEach { insertTrip(routeId, it, "${it}T22:10:00Z") }
        val sixTrips = statementsFor("routeId=$routeId", expectedTrips = 6)
        assertEquals(oneTrip, sixTrips)
    }

    @Test
    fun `create and patch responses carry today's recommendation before any evaluation exists`() {
        val routeId = insertRoute()
        insertRecommendation(
            routeId,
            "2026-10-07",
            "2026-10-07T00:00:00Z",
            "2026-10-06T22:24:00Z",
            "v2",
            "2026-10-06T21:00:00Z",
        )

        val created =
            mockMvc
                .post("/api/v1/commute-trips") {
                    header(ApiTokenFilter.HEADER, properties.apiToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"routeId":$routeId,"tripDate":"2026-10-07","leftHomeAt":"2026-10-06T22:27:00Z"}"""
                }.andExpect { status { isCreated() } }
                .json()
        assertEquals("2026-10-06T22:24:00Z", created["recommendation"]["recommendedLeaveHomeAt"].asText())

        val patched =
            mockMvc
                .patch("/api/v1/commute-trips/${created["id"].asLong()}") {
                    header(ApiTokenFilter.HEADER, properties.apiToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"arrivedDestinationAt":"2026-10-06T23:58:00Z"}"""
                }.andExpect { status { isOk() } }
                .json()
        assertEquals("v2", patched["recommendation"]["modelVersion"].asText())
        assertEquals("2026-10-07T00:00:00Z", patched["recommendation"]["targetArrivalAt"].asText())
    }

    // --- fixtures ---

    private fun statementsFor(
        query: String,
        expectedTrips: Int,
    ): Long {
        statistics.isStatisticsEnabled = true
        statistics.clear()
        val trips = search(query).andExpect { status { isOk() } }.json()
        assertEquals(expectedTrips, trips.size())
        trips.forEach { assertEquals("v2", it["recommendation"]["modelVersion"].asText()) }
        return statistics.prepareStatementCount
    }

    private fun search(query: String): ResultActionsDsl =
        mockMvc.get("/api/v1/commute-trips?$query") {
            header(ApiTokenFilter.HEADER, properties.apiToken)
        }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)

    private fun insertRoute(): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_routes (user_id, name, direction, origin_lat, origin_lng, destination_lat, destination_lng)
            VALUES (?, '출근', 'TO_WORK', 37.2, 127.07, 37.49, 127.1) RETURNING id
            """.trimIndent(),
            Long::class.java,
            DefaultUser.ID,
        )!!

    private fun insertTrip(
        routeId: Long,
        tripDate: String,
        leftHomeAt: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_trips (user_id, commute_route_id, trip_date, left_home_at)
            VALUES (?, ?, ?::date, ?::timestamptz) RETURNING id
            """.trimIndent(),
            Long::class.java,
            DefaultUser.ID,
            routeId,
            tripDate,
            leftHomeAt,
        )!!

    private fun insertRecommendation(
        routeId: Long,
        targetDate: String,
        targetArrivalAt: String,
        leaveHomeAt: String,
        modelVersion: String,
        computedAt: String,
        catchProbability: Double = 0.9,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO departure_recommendations (user_id, commute_route_id, target_date, target_arrival_at,
                recommended_leave_home_at, catch_probability, buffer_seconds, model_version, computed_at)
            VALUES (?, ?, ?::date, ?::timestamptz, ?::timestamptz, ?, 600, ?, ?::timestamptz)
            """.trimIndent(),
            DefaultUser.ID,
            routeId,
            targetDate,
            targetArrivalAt,
            leaveHomeAt,
            catchProbability,
            modelVersion,
            computedAt,
        )
    }
}
