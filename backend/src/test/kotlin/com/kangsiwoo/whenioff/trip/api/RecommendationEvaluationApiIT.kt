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
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /commute-routes/{id}/recommendation-evaluations` (#79). 평가 행은 analytics `evaluate`가 쓰는 테이블이라
 * 원본(추천·trip)과 함께 SQL로 직접 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationEvaluationApiIT {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute(
            "TRUNCATE recommendation_evaluations, departure_recommendations, commute_trips, commute_routes " +
                "RESTART IDENTITY CASCADE",
        )
        jdbcTemplate.update("DELETE FROM users WHERE id <> ?", DefaultUser.ID)
    }

    @Test
    fun `summaries follow the analytics definitions and skip nulls`() {
        val route = insertRoute(DefaultUser.ID)
        // v1: 지각 / 제시간 / 도착 기록 없음. 출발 차이·대기는 비는 행이 하나씩 있다.
        insertEvaluation(route, "2026-09-21", "v1", Eval(late = true, departureDiff = 120, wait = 300, caught = true))
        insertEvaluation(
            route,
            "2026-09-22",
            "v1",
            Eval(late = false, departureDiff = -60, wait = null, caught = false),
        )
        insertEvaluation(
            route,
            "2026-09-23",
            "v1",
            Eval(late = null, departureDiff = null, wait = 500, caught = true, missed = 2),
        )
        // v2: 값이 모두 빈 한 행 → 비율·평균이 빠진다(0으로 채우지 않는다).
        insertEvaluation(
            route,
            "2026-09-21",
            "v2",
            Eval(late = null, departureDiff = null, wait = null, caught = false),
        )

        val body = evaluations(route, "2026-09-21", "2026-09-23").andExpect { status { isOk() } }.json()

        val summaries = body["summaries"]
        assertEquals(listOf("v1", "v2"), summaries.map { it["modelVersion"].asText() })
        val v1 = summaries[0]
        assertEquals(3, v1["n"].asInt())
        assertEquals(1, v1["lateCount"].asInt())
        assertEquals(2, v1["withArrival"].asInt())
        assertEquals(0.5, v1["lateRate"].asDouble())
        assertEquals(30.0, v1["meanDepartureDiffSec"].asDouble())
        assertEquals(400.0, v1["meanStopWaitSec"].asDouble())
        assertEquals(2, v1["allLegsCaughtCount"].asInt())
        assertEquals(2.0 / 3, v1["allLegsCaughtRate"].asDouble(), 1e-12)
        val v2 = summaries[1]
        assertEquals(1, v2["n"].asInt())
        assertEquals(0, v2["lateCount"].asInt())
        assertEquals(0, v2["withArrival"].asInt())
        assertFalse(v2.has("lateRate"))
        assertFalse(v2.has("meanDepartureDiffSec"))
        assertFalse(v2.has("meanStopWaitSec"))
        assertEquals(0.0, v2["allLegsCaughtRate"].asDouble())

        val rows = body["evaluations"]
        assertEquals(
            listOf("2026-09-21/v1", "2026-09-21/v2", "2026-09-22/v1", "2026-09-23/v1"),
            rows.map { "${it["date"].asText()}/${it["modelVersion"].asText()}" },
        )
        val first = rows[0]
        assertTrue(first["isLate"].asBoolean())
        assertEquals(120, first["departureDiffSec"].asInt())
        assertEquals(300, first["avgStopWaitSec"].asInt())
        assertEquals(330, first["arrivalDiffSec"].asInt())
        assertEquals("2026-09-21T00:00:00Z", first["targetArrivalAt"].asText())
        assertEquals("2026-09-20T22:20:00Z", first["recommendedLeaveHomeAt"].asText())
        assertEquals("2026-09-20T22:22:00Z", first["actualLeftHomeAt"].asText())
        assertEquals("2026-09-21T00:05:30Z", first["actualArrivedAt"].asText())
        assertTrue(first["allLegsCaught"].asBoolean())
        assertTrue(first.has("evaluatedAt"))
        val noArrival = rows[3]
        assertFalse(noArrival.has("isLate"))
        assertFalse(noArrival.has("actualArrivedAt"))
        assertFalse(noArrival.has("departureDiffSec"))
        assertEquals(2, noArrival["missedCount"].asInt())
    }

    @Test
    fun `only the range is read and an empty range gives empty arrays`() {
        val route = insertRoute(DefaultUser.ID)
        listOf("2026-09-20", "2026-09-21", "2026-09-23", "2026-09-24").forEach {
            insertEvaluation(route, it, "v1", Eval(late = false, departureDiff = 0, wait = 60, caught = true))
        }

        val body = evaluations(route, "2026-09-21", "2026-09-23").andExpect { status { isOk() } }.json()
        assertEquals(listOf("2026-09-21", "2026-09-23"), body["evaluations"].map { it["date"].asText() })
        assertEquals(2, body["summaries"][0]["n"].asInt())

        val none = evaluations(route, "2026-10-01", "2026-10-31").andExpect { status { isOk() } }.json()
        assertEquals(0, none["summaries"].size())
        assertEquals(0, none["evaluations"].size())
    }

    @Test
    fun `other routes' evaluations are not mixed in`() {
        val route = insertRoute(DefaultUser.ID)
        val other = insertRoute(DefaultUser.ID)
        insertEvaluation(other, "2026-09-21", "v1", Eval(late = true, departureDiff = 600, wait = 60, caught = true))
        insertEvaluation(route, "2026-09-21", "v1", Eval(late = false, departureDiff = 0, wait = 60, caught = true))

        val body = evaluations(route, "2026-09-01", "2026-09-30").andExpect { status { isOk() } }.json()
        assertEquals(1, body["evaluations"].size())
        assertEquals(0, body["summaries"][0]["lateCount"].asInt())
    }

    @Test
    fun `invalid ranges give 400`() {
        val route = insertRoute(DefaultUser.ID)
        evaluations(route, "2026-09-22", "2026-09-21")
            .andExpect { status { isBadRequest() } }
            .andExpect { content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) } }
        get("/api/v1/commute-routes/$route/recommendation-evaluations?from=2026-09-21")
            .andExpect { status { isBadRequest() } }
        get("/api/v1/commute-routes/$route/recommendation-evaluations?to=2026-09-21")
            .andExpect { status { isBadRequest() } }
        evaluations(route, "2026-09-21T00:00:00Z", "2026-09-22").andExpect { status { isBadRequest() } }
        evaluations(route, "2026-09-21", "2026-09-21").andExpect { status { isOk() } }
    }

    @Test
    fun `unknown route and another user's route give 404`() {
        evaluations(999_999, "2026-09-21", "2026-09-21").andExpect { status { isNotFound() } }
        val otherUser =
            jdbcTemplate.queryForObject(
                "INSERT INTO users (email, display_name) VALUES ('other@when-i-off.local', 'other') RETURNING id",
                Long::class.java,
            )!!
        val otherRoute = insertRoute(otherUser)
        insertEvaluation(otherRoute, "2026-09-21", "v1", Eval(late = true, departureDiff = 0, wait = 0, caught = true))
        evaluations(otherRoute, "2026-09-21", "2026-09-21").andExpect { status { isNotFound() } }
    }

    @Test
    fun `requires the api token`() {
        val route = insertRoute(DefaultUser.ID)
        mockMvc
            .get("/api/v1/commute-routes/$route/recommendation-evaluations?from=2026-09-21&to=2026-09-21")
            .andExpect { status { isUnauthorized() } }
    }

    // --- fixtures ---

    /** 평가 한 행의 값. 시각은 목표 09:00 KST(00:00Z), 추천 출발 07:20 KST에서 차이로 만든다. */
    private data class Eval(
        val late: Boolean?,
        val departureDiff: Int?,
        val wait: Int?,
        val caught: Boolean,
        val missed: Int = 0,
    )

    private fun insertRoute(userId: Long): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_routes (user_id, name, direction, origin_lat, origin_lng, destination_lat, destination_lng)
            VALUES (?, '출근', 'TO_WORK', 37.2, 127.07, 37.49, 127.1) RETURNING id
            """.trimIndent(),
            Long::class.java,
            userId,
        )!!

    private fun insertEvaluation(
        routeId: Long,
        date: String,
        version: String,
        e: Eval,
    ) {
        val target = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant()
        val recommended = target.minusSeconds(100 * 60)
        val left = e.departureDiff?.let { recommended.plusSeconds(it.toLong()) }
        // 지각이면 5분 30초 늦게, 제시간이면 2분 일찍 도착. 도착 기록이 없으면 null.
        val arrivalDiff =
            when (e.late) {
                true -> 330
                false -> -120
                null -> null
            }
        val arrived = arrivalDiff?.let { target.plusSeconds(it.toLong()) }
        val recommendationId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO departure_recommendations (user_id, commute_route_id, target_date, target_arrival_at,
                    recommended_leave_home_at, catch_probability, buffer_seconds, model_version)
                SELECT user_id, id, ?::date, ?::timestamptz, ?::timestamptz, 0.9, 600, ? FROM commute_routes
                WHERE id = ? RETURNING id
                """.trimIndent(),
                Long::class.java,
                date,
                target.toString(),
                recommended.toString(),
                version,
                routeId,
            )!!
        val tripId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO commute_trips (user_id, commute_route_id, trip_date, left_home_at, arrived_destination_at)
                SELECT user_id, id, ?::date, ?::timestamptz, ?::timestamptz FROM commute_routes WHERE id = ?
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                date,
                left?.toString(),
                arrived?.toString(),
                routeId,
            )!!
        jdbcTemplate.update(
            """
            INSERT INTO recommendation_evaluations (commute_route_id, target_date, model_version,
                departure_recommendation_id, commute_trip_id, target_arrival_at, recommended_leave_home_at,
                actual_left_home_at, actual_arrived_at, departure_diff_sec, arrival_diff_sec, is_late,
                all_legs_caught, missed_count, avg_stop_wait_sec)
            VALUES (?, ?::date, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            routeId,
            date,
            version,
            recommendationId,
            tripId,
            target.toString(),
            recommended.toString(),
            left?.toString(),
            arrived?.toString(),
            e.departureDiff,
            arrivalDiff,
            e.late,
            e.caught,
            e.missed,
            e.wait,
        )
    }

    private fun evaluations(
        routeId: Long,
        from: String,
        to: String,
    ): ResultActionsDsl = get("/api/v1/commute-routes/$routeId/recommendation-evaluations?from=$from&to=$to")

    private fun get(url: String): ResultActionsDsl =
        mockMvc.get(url) {
            header(ApiTokenFilter.HEADER, properties.apiToken)
        }

    private fun ResultActionsDsl.json(): JsonNode =
        objectMapper.readTree(andReturn().response.getContentAsString(Charsets.UTF_8))
}
