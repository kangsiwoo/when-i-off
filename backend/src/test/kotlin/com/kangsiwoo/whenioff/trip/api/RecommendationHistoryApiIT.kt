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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /commute-routes/{id}/recommendation-history` (#62). 추천은 analytics가, trip은 앱이 쓰는 테이블이라
 * 둘 다 SQL로 직접 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationHistoryApiIT {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute(
            "TRUNCATE departure_recommendations, commute_trips, commute_routes, transit_lines, transit_stops " +
                "RESTART IDENTITY CASCADE",
        )
        jdbcTemplate.update("DELETE FROM users WHERE id <> ?", DefaultUser.ID)
    }

    @Test
    fun `several computations on one day give the last one per model version`() {
        val f = seedRoute()
        // v1: 같은 목표 시각을 세 번 계산했다 (예전 데이터처럼 행이 쌓인 경우). 마지막 계산이 나온다.
        insertRecommendation(
            f.routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:20:00Z",
            900,
            "v1",
            "2026-09-20T18:00:00Z",
        )
        insertRecommendation(
            f.routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:26:00Z",
            540,
            "v1",
            "2026-09-20T21:00:00Z",
        )
        insertRecommendation(
            f.routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:23:00Z",
            720,
            "v1",
            "2026-09-20T19:00:00Z",
        )
        // v2는 따로 한 건. v1의 마지막 계산보다 이르지만 버전별로 나뉘므로 같이 나온다.
        insertRecommendation(
            f.routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:30:00Z",
            300,
            "v2",
            "2026-09-20T20:00:00Z",
            0.83,
        )

        val days = history(f.routeId, "2026-09-21", "2026-09-21").andExpect { status { isOk() } }.json()
        assertEquals(1, days.size())
        val day = days[0]
        assertEquals("2026-09-21", day["date"].asText())
        assertEquals(0, day["trips"].size())
        val recs = day["recommendations"]
        assertEquals(listOf("v1", "v2"), recs.map { it["modelVersion"].asText() })
        assertEquals("2026-09-20T22:26:00Z", recs[0]["recommendedLeaveHomeAt"].asText())
        assertEquals(540, recs[0]["bufferSeconds"].asInt())
        assertEquals("2026-09-20T21:00:00Z", recs[0]["computedAt"].asText())
        assertEquals("2026-09-21T00:00:00Z", recs[0]["targetArrivalAt"].asText())
        assertEquals("2026-09-20T22:30:00Z", recs[1]["recommendedLeaveHomeAt"].asText())
        assertEquals(0.83, recs[1]["catchProbability"].asDouble())
    }

    @Test
    fun `with several target arrival times the latest computed wins and its target is exposed`() {
        val f = seedRoute()
        insertRecommendation(
            f.routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:24:00Z",
            600,
            "v1",
            "2026-09-20T21:30:00Z",
        )
        insertRecommendation(
            f.routeId,
            "2026-09-21",
            "2026-09-21T00:30:00Z",
            "2026-09-20T22:52:00Z",
            480,
            "v1",
            "2026-09-20T21:45:00Z",
        )
        // computed_at이 같으면 나중에 들어간 행(큰 id)이다.
        insertRecommendation(
            f.routeId,
            "2026-09-22",
            "2026-09-22T00:00:00Z",
            "2026-09-21T22:20:00Z",
            700,
            "v1",
            "2026-09-21T21:00:00Z",
        )
        insertRecommendation(
            f.routeId,
            "2026-09-22",
            "2026-09-22T00:10:00Z",
            "2026-09-21T22:31:00Z",
            650,
            "v1",
            "2026-09-21T21:00:00Z",
        )

        val days = history(f.routeId, "2026-09-21", "2026-09-22").andExpect { status { isOk() } }.json()
        assertEquals(listOf("2026-09-21", "2026-09-22"), days.map { it["date"].asText() })
        val first = days[0]["recommendations"]
        assertEquals(1, first.size())
        assertEquals("2026-09-21T00:30:00Z", first[0]["targetArrivalAt"].asText())
        assertEquals("2026-09-20T22:52:00Z", first[0]["recommendedLeaveHomeAt"].asText())
        val second = days[1]["recommendations"]
        assertEquals("2026-09-22T00:10:00Z", second[0]["targetArrivalAt"].asText())
    }

    @Test
    fun `days with only trips or only recommendations are included in date order`() {
        val f = seedRoute()
        // 9/21: 추천만, 9/22: trip만, 9/23: 둘 다, 9/20·9/24: 범위 밖.
        insertRecommendation(
            f.routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:24:00Z",
            600,
            "v1",
            "2026-09-20T21:00:00Z",
        )
        insertRecommendation(
            f.routeId,
            "2026-09-23",
            "2026-09-23T00:00:00Z",
            "2026-09-22T22:24:00Z",
            600,
            "v1",
            "2026-09-22T21:00:00Z",
        )
        insertRecommendation(
            f.routeId,
            "2026-09-20",
            "2026-09-20T00:00:00Z",
            "2026-09-19T22:24:00Z",
            600,
            "v1",
            "2026-09-19T21:00:00Z",
        )
        insertTrip(f.routeId, "2026-09-23", "2026-09-22T22:27:00Z", "2026-09-22T23:58:00Z")
        insertTrip(f.routeId, "2026-09-22", "2026-09-21T22:30:00Z", "2026-09-22T00:04:00Z")
        insertTrip(f.routeId, "2026-09-24", "2026-09-23T22:30:00Z", null)

        val days = history(f.routeId, "2026-09-21", "2026-09-23").andExpect { status { isOk() } }.json()
        assertEquals(listOf("2026-09-21", "2026-09-22", "2026-09-23"), days.map { it["date"].asText() })
        assertEquals(listOf(1, 0, 1), days.map { it["recommendations"].size() })
        assertEquals(listOf(0, 1, 1), days.map { it["trips"].size() })
        val trip = days[1]["trips"][0]
        assertEquals("2026-09-21T22:30:00Z", trip["leftHomeAt"].asText())
        assertEquals("2026-09-22T00:04:00Z", trip["arrivedDestinationAt"].asText())

        // 아무것도 없는 기간은 빈 배열이다 (404가 아니다).
        val none = history(f.routeId, "2026-10-01", "2026-10-31").andExpect { status { isOk() } }.json()
        assertEquals(0, none.size())
    }

    @Test
    fun `trips of the day carry whether every transit leg was caught and how many vehicles were missed`() {
        val f = seedRoute()
        // 두 번째 trip이 먼저 들어가도 leftHomeAt 순으로 나온다. leftHomeAt이 없는 trip은 맨 뒤.
        val noStart = insertTrip(f.routeId, "2026-09-23", null, null)
        val late = insertTrip(f.routeId, "2026-09-23", "2026-09-23T09:00:00Z", "2026-09-23T10:20:00Z")
        val early = insertTrip(f.routeId, "2026-09-23", "2026-09-22T22:27:00Z", "2026-09-23T00:03:00Z")
        // early: 첫 TRANSIT 구간에서 한 대 놓치고 다음 차를 탔고, 두 번째 구간도 탔다.
        insertAttempt(early, f.transitLegIds[0], 1, "MISSED")
        insertAttempt(early, f.transitLegIds[0], 2, "CAUGHT")
        insertAttempt(early, f.transitLegIds[1], 1, "CAUGHT")
        // late: 두 번째 구간은 탄 기록이 없다 (UNKNOWN) → 전 구간 탑승 아님. 놓친 차 둘.
        insertAttempt(late, f.transitLegIds[0], 1, "MISSED")
        insertAttempt(late, f.transitLegIds[0], 2, "MISSED")
        insertAttempt(late, f.transitLegIds[0], 3, "CAUGHT")
        insertAttempt(late, f.transitLegIds[1], 1, "UNKNOWN")

        val trips =
            history(f.routeId, "2026-09-23", "2026-09-23")
                .andExpect { status { isOk() } }
                .json()[0]["trips"]
        assertEquals(listOf(early, late, noStart), trips.map { it["tripId"].asLong() })
        assertTrue(trips[0]["allLegsCaught"].asBoolean())
        assertEquals(1, trips[0]["missedCount"].asInt())
        assertFalse(trips[1]["allLegsCaught"].asBoolean())
        assertEquals(2, trips[1]["missedCount"].asInt())
        assertFalse(trips[2]["allLegsCaught"].asBoolean())
        assertEquals(0, trips[2]["missedCount"].asInt())
        assertFalse(trips[2].has("leftHomeAt"))
    }

    @Test
    fun `other routes' recommendations and trips are not mixed in`() {
        val f = seedRoute()
        val other = seedRoute()
        insertRecommendation(
            other.routeId,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:24:00Z",
            600,
            "v1",
            "2026-09-20T21:00:00Z",
        )
        insertTrip(other.routeId, "2026-09-21", "2026-09-20T22:27:00Z", null)
        insertRecommendation(
            f.routeId,
            "2026-09-22",
            "2026-09-22T00:00:00Z",
            "2026-09-21T22:24:00Z",
            600,
            "v1",
            "2026-09-21T21:00:00Z",
        )

        val days = history(f.routeId, "2026-09-01", "2026-09-30").andExpect { status { isOk() } }.json()
        assertEquals(listOf("2026-09-22"), days.map { it["date"].asText() })
    }

    @Test
    fun `invalid ranges give 400`() {
        val f = seedRoute()
        history(f.routeId, "2026-09-22", "2026-09-21")
            .andExpect { status { isBadRequest() } }
            .andExpect { content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) } }
        get("/api/v1/commute-routes/${f.routeId}/recommendation-history?from=2026-09-21")
            .andExpect { status { isBadRequest() } }
        get("/api/v1/commute-routes/${f.routeId}/recommendation-history?to=2026-09-21")
            .andExpect { status { isBadRequest() } }
        history(f.routeId, "2026-09-21T00:00:00Z", "2026-09-22").andExpect { status { isBadRequest() } }
        // 하루짜리 범위(from == to)는 맞다.
        history(f.routeId, "2026-09-21", "2026-09-21").andExpect { status { isOk() } }
    }

    @Test
    fun `unknown route and another user's route give 404`() {
        history(999_999, "2026-09-21", "2026-09-21").andExpect { status { isNotFound() } }
        val otherUser =
            jdbcTemplate.queryForObject(
                "INSERT INTO users (email, display_name) VALUES ('other@when-i-off.local', 'other') RETURNING id",
                Long::class.java,
            )!!
        val otherRoute = insertRoute(otherUser)
        insertRecommendation(
            otherRoute,
            "2026-09-21",
            "2026-09-21T00:00:00Z",
            "2026-09-20T22:24:00Z",
            600,
            "v1",
            "2026-09-20T21:00:00Z",
        )
        history(otherRoute, "2026-09-21", "2026-09-21").andExpect { status { isNotFound() } }
    }

    @Test
    fun `requires the api token`() {
        val f = seedRoute()
        mockMvc
            .get("/api/v1/commute-routes/${f.routeId}/recommendation-history?from=2026-09-21&to=2026-09-21")
            .andExpect { status { isUnauthorized() } }
    }

    // --- fixtures ---

    private data class Seeded(
        val routeId: Long,
        val transitLegIds: List<Long>,
    )

    /** WALK(1) → TRANSIT(2) → WALK(3) → TRANSIT(4) → WALK(5). */
    private fun seedRoute(): Seeded {
        val lineId =
            jdbcTemplate.queryForObject(
                "INSERT INTO transit_lines (mode, name, has_realtime_api) VALUES ('GTX', 'GTX-A', false) RETURNING id",
                Long::class.java,
            )!!
        val stops = (1..3).map { insertStop("역 $it") }
        val routeId = insertRoute(DefaultUser.ID)
        insertWalkLeg(routeId, 1)
        val t1 = insertTransitLeg(routeId, 2, lineId, stops[0], stops[1])
        insertWalkLeg(routeId, 3)
        val t2 = insertTransitLeg(routeId, 4, lineId, stops[1], stops[2])
        insertWalkLeg(routeId, 5)
        return Seeded(routeId, listOf(t1, t2))
    }

    private fun insertStop(name: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO transit_stops (mode, name, lat, lng) VALUES ('GTX', ?, 37.2, 127.09) RETURNING id",
            Long::class.java,
            name,
        )!!

    private fun insertRoute(userId: Long): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_routes (user_id, name, direction, origin_lat, origin_lng, destination_lat, destination_lng)
            VALUES (?, '출근', 'TO_WORK', 37.2, 127.07, 37.49, 127.1) RETURNING id
            """.trimIndent(),
            Long::class.java,
            userId,
        )!!

    private fun insertWalkLeg(
        routeId: Long,
        seq: Int,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO route_legs (commute_route_id, seq_order, leg_type, start_lat, start_lng, end_lat, end_lng,
                                    planned_distance_m)
            VALUES (?, ?, 'WALK', 37.2, 127.07, 37.2, 127.09, 650) RETURNING id
            """.trimIndent(),
            Long::class.java,
            routeId,
            seq,
        )!!

    private fun insertTransitLeg(
        routeId: Long,
        seq: Int,
        lineId: Long,
        boardId: Long,
        alightId: Long,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO route_legs (commute_route_id, seq_order, leg_type, transit_line_id, board_stop_id,
                                    alight_stop_id, planned_travel_sec)
            VALUES (?, ?, 'TRANSIT', ?, ?, ?, 1200) RETURNING id
            """.trimIndent(),
            Long::class.java,
            routeId,
            seq,
            lineId,
            boardId,
            alightId,
        )!!

    private fun insertRecommendation(
        routeId: Long,
        targetDate: String,
        targetArrivalAt: String,
        leaveHomeAt: String,
        bufferSeconds: Int,
        modelVersion: String,
        computedAt: String,
        catchProbability: Double = 0.9,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO departure_recommendations (user_id, commute_route_id, target_date, target_arrival_at,
                recommended_leave_home_at, catch_probability, buffer_seconds, model_version, computed_at)
            SELECT user_id, id, ?::date, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?::timestamptz
            FROM commute_routes WHERE id = ?
            """.trimIndent(),
            targetDate,
            targetArrivalAt,
            leaveHomeAt,
            catchProbability,
            bufferSeconds,
            modelVersion,
            computedAt,
            routeId,
        )
    }

    private fun insertTrip(
        routeId: Long,
        tripDate: String,
        leftHomeAt: String?,
        arrivedAt: String?,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_trips (user_id, commute_route_id, trip_date, left_home_at, arrived_destination_at)
            SELECT user_id, id, ?::date, ?::timestamptz, ?::timestamptz FROM commute_routes WHERE id = ?
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            tripDate,
            leftHomeAt,
            arrivedAt,
            routeId,
        )!!

    private fun insertAttempt(
        tripId: Long,
        legId: Long,
        seq: Int,
        result: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO boarding_attempts (commute_trip_id, route_leg_id, attempt_seq, result)
            VALUES (?, ?, ?, ?::boarding_result)
            """.trimIndent(),
            tripId,
            legId,
            seq,
            result,
        )
    }

    private fun history(
        routeId: Long,
        from: String,
        to: String,
    ): ResultActionsDsl = get("/api/v1/commute-routes/$routeId/recommendation-history?from=$from&to=$to")

    private fun get(url: String): ResultActionsDsl =
        mockMvc.get(url) {
            header(ApiTokenFilter.HEADER, properties.apiToken)
        }

    private fun ResultActionsDsl.json(): JsonNode =
        objectMapper.readTree(andReturn().response.getContentAsString(Charsets.UTF_8))
}
