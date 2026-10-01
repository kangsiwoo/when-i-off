package com.kangsiwoo.whenioff.route.api

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
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /commute-routes/{id}/calibration` (#60). 보정 행은 analytics가 쓰는 테이블에 SQL로 직접 넣는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalibrationApiIT {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute(
            "TRUNCATE user_walking_profile, transit_prediction_calibration, transit_travel_time_calibration, " +
                "commute_routes, transit_lines, transit_stops CASCADE",
        )
        jdbcTemplate.update("DELETE FROM users WHERE id <> ?", DefaultUser.ID)
    }

    @Test
    fun `route without calibration rows gives empty rows and minSamples`() {
        val f = seedRoute()

        val body = getCalibration(f.routeId).andExpect { status { isOk() } }.json()
        assertEquals(f.routeId, body["routeId"].asLong())
        assertEquals(5, body["minSamples"].asInt())
        assertFalse(body.has("globalWalkingProfile"))

        val legs = body["legs"]
        assertEquals(listOf(1, 2, 3), legs.map { it["seqOrder"].asInt() })
        assertEquals(listOf("WALK", "TRANSIT", "WALK"), legs.map { it["legType"].asText() })
        assertFalse(legs[0].has("walkingProfile"))
        assertEquals(650.0, legs[0]["plannedDistanceM"].asDouble())

        val transit = legs[1]
        assertEquals(f.lineId, transit["transitLineId"].asLong())
        assertEquals("GTX-A", transit["transitLineName"].asText())
        assertEquals(f.boardId, transit["boardStopId"].asLong())
        assertEquals("동탄", transit["boardStopName"].asText())
        assertEquals(f.alightId, transit["alightStopId"].asLong())
        assertEquals("수서", transit["alightStopName"].asText())
        assertEquals(1200, transit["plannedTravelSec"].asInt())
        assertEquals(0, transit["predictionRows"].size())
        assertEquals(0, transit["travelTimeRows"].size())
    }

    @Test
    fun `returns leg and global walking rows and all band rows of the transit leg in order`() {
        val f = seedRoute()
        insertWalking(f.walkLegIds[0], 1.31, 0.12, 8)
        insertWalking(null, 1.25, 0.2, 30)
        // 순서가 섞여 들어가도 day_type(WEEKDAY → SATURDAY → SUNDAY_HOLIDAY) → 밴드 시작 순으로 나온다.
        insertPrediction(f.lineId, f.boardId, "SATURDAY", "07:30", "08:00", bias = 10, stddev = 40, n = 2)
        insertPrediction(f.lineId, f.boardId, "WEEKDAY", "23:30", "23:59:59.999999", bias = -5, stddev = 30, n = 1)
        insertPrediction(f.lineId, f.boardId, "WEEKDAY", "07:30", "08:00", bias = 20, stddev = 45, n = 12)
        insertTravel(f.lineId, f.boardId, f.alightId, "WEEKDAY", "07:30", "08:00", mean = 1180, stddev = 60, n = 9)

        val body = getCalibration(f.routeId).andExpect { status { isOk() } }.json()

        val global = body["globalWalkingProfile"]
        assertEquals(1.25, global["avgSpeedMps"].asDouble())
        assertEquals(0.2, global["stddevSpeedMps"].asDouble())
        assertEquals(30, global["sampleCount"].asInt())
        assertTrue(global["updatedAt"].asText().endsWith("Z"))

        val legs = body["legs"]
        assertEquals(1.31, legs[0]["walkingProfile"]["avgSpeedMps"].asDouble())
        assertEquals(8, legs[0]["walkingProfile"]["sampleCount"].asInt())
        assertFalse(legs[2].has("walkingProfile"), "leg 3 has no row of its own")
        assertFalse(legs[1].has("walkingProfile"))

        val prediction = legs[1]["predictionRows"]
        assertEquals(
            listOf("WEEKDAY 07:30-08:00", "WEEKDAY 23:30-24:00", "SATURDAY 07:30-08:00"),
            prediction.map {
                "${it["dayType"].asText()} ${it["timeBandStart"].asText()}-${it["timeBandEnd"].asText()}"
            },
        )
        assertEquals(20, prediction[0]["biasSec"].asInt())
        assertEquals(45, prediction[0]["stddevSec"].asInt())
        assertEquals(12, prediction[0]["sampleCount"].asInt())
        assertEquals(-5, prediction[1]["biasSec"].asInt())

        val travel = legs[1]["travelTimeRows"]
        assertEquals(1, travel.size())
        assertEquals(1180, travel[0]["meanSec"].asInt())
        assertEquals(60, travel[0]["stddevSec"].asInt())
        assertEquals(9, travel[0]["sampleCount"].asInt())
        assertEquals("07:30", travel[0]["timeBandStart"].asText())
    }

    @Test
    fun `rows of other legs, lines, stops and users are not mixed in`() {
        val f = seedRoute()
        // 같은 사용자의 다른 경로 구간 행 — 이 경로 응답에 나오면 안 된다.
        val other = seedRoute(lineName = "GTX-B", boardName = "판교", alightName = "삼성")
        insertWalking(other.walkLegIds[0], 1.0, 0.1, 9)
        // 같은 노선의 다른 정류장, 다른 노선의 같은 정류장, 하차역만 다른 차내 시간.
        val otherStop = insertStop("성남")
        insertPrediction(f.lineId, otherStop, "WEEKDAY", "07:30", "08:00", bias = 99, stddev = 99, n = 9)
        insertPrediction(other.lineId, f.boardId, "WEEKDAY", "07:30", "08:00", bias = 98, stddev = 98, n = 9)
        insertTravel(f.lineId, f.boardId, otherStop, "WEEKDAY", "07:30", "08:00", mean = 600, stddev = 30, n = 9)
        insertTravel(other.lineId, f.boardId, f.alightId, "WEEKDAY", "07:30", "08:00", mean = 700, stddev = 30, n = 9)
        // 다른 사용자의 전역 행.
        val otherUser = insertUser()
        jdbcTemplate.update(
            "INSERT INTO user_walking_profile (user_id, avg_speed_mps, stddev_speed_mps, sample_count) VALUES (?, 2.0, 0.1, 50)",
            otherUser,
        )

        val body = getCalibration(f.routeId).andExpect { status { isOk() } }.json()
        assertFalse(body.has("globalWalkingProfile"))
        val legs = body["legs"]
        assertFalse(legs[0].has("walkingProfile"))
        assertEquals(0, legs[1]["predictionRows"].size())
        assertEquals(0, legs[1]["travelTimeRows"].size())

        // 다른 경로 쪽에서는 자기 구간 행이 보인다.
        val otherBody = getCalibration(other.routeId).andExpect { status { isOk() } }.json()
        assertEquals(9, otherBody["legs"][0]["walkingProfile"]["sampleCount"].asInt())
    }

    @Test
    fun `unknown route and another user's route give 404`() {
        getCalibration(999_999)
            .andExpect { status { isNotFound() } }
            .andExpect { content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) } }

        val otherUser = insertUser()
        val otherRoute = insertRoute(otherUser, "남의 출근")
        getCalibration(otherRoute).andExpect { status { isNotFound() } }
    }

    @Test
    fun `requires the api token`() {
        val f = seedRoute()
        mockMvc.get("/api/v1/commute-routes/${f.routeId}/calibration").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `band boundaries are written as HH mm with the last end as 24 00`() {
        assertEquals("00:00", timeBandText(LocalTime.MIDNIGHT))
        assertEquals("07:30", timeBandText(LocalTime.of(7, 30)))
        assertEquals("23:30", timeBandText(LocalTime.of(23, 30)))
        assertEquals("24:00", timeBandText(LocalTime.MAX))
    }

    // --- fixtures ---

    private data class Seeded(
        val routeId: Long,
        val lineId: Long,
        val boardId: Long,
        val alightId: Long,
        val walkLegIds: List<Long>,
    )

    /** WALK(1) → TRANSIT(2) → WALK(3) 경로 하나. */
    private fun seedRoute(
        lineName: String = "GTX-A",
        boardName: String = "동탄",
        alightName: String = "수서",
    ): Seeded {
        val lineId =
            jdbcTemplate.queryForObject(
                "INSERT INTO transit_lines (mode, name, has_realtime_api) VALUES ('GTX', ?, false) RETURNING id",
                Long::class.java,
                lineName,
            )!!
        val boardId = insertStop(boardName)
        val alightId = insertStop(alightName)
        val routeId = insertRoute(DefaultUser.ID, "$boardName → $alightName")
        val walk1 = insertWalkLeg(routeId, 1)
        jdbcTemplate.update(
            """
            INSERT INTO route_legs (commute_route_id, seq_order, leg_type, transit_line_id, board_stop_id,
                                    alight_stop_id, planned_travel_sec)
            VALUES (?, 2, 'TRANSIT', ?, ?, ?, 1200)
            """.trimIndent(),
            routeId,
            lineId,
            boardId,
            alightId,
        )
        val walk3 = insertWalkLeg(routeId, 3)
        return Seeded(routeId, lineId, boardId, alightId, listOf(walk1, walk3))
    }

    private fun insertUser(): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, display_name) VALUES ('other@when-i-off.local', 'other') RETURNING id",
            Long::class.java,
        )!!

    private fun insertStop(name: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO transit_stops (mode, name, lat, lng) VALUES ('GTX', ?, 37.2, 127.09) RETURNING id",
            Long::class.java,
            name,
        )!!

    private fun insertRoute(
        userId: Long,
        name: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_routes (user_id, name, direction, origin_lat, origin_lng, destination_lat, destination_lng)
            VALUES (?, ?, 'TO_WORK', 37.2, 127.07, 37.49, 127.1) RETURNING id
            """.trimIndent(),
            Long::class.java,
            userId,
            name,
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

    private fun insertWalking(
        legId: Long?,
        avg: Double,
        stddev: Double,
        n: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_walking_profile (user_id, route_leg_id, avg_speed_mps, stddev_speed_mps, sample_count)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            DefaultUser.ID,
            legId,
            avg,
            stddev,
            n,
        )
    }

    private fun insertPrediction(
        lineId: Long,
        stopId: Long,
        dayType: String,
        start: String,
        end: String,
        bias: Int,
        stddev: Int,
        n: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO transit_prediction_calibration (transit_line_id, stop_id, day_type, time_band_start,
                                                        time_band_end, bias_sec, stddev_sec, sample_count)
            VALUES (?, ?, ?::day_type, ?::time, ?::time, ?, ?, ?)
            """.trimIndent(),
            lineId,
            stopId,
            dayType,
            start,
            end,
            bias,
            stddev,
            n,
        )
    }

    private fun insertTravel(
        lineId: Long,
        boardId: Long,
        alightId: Long,
        dayType: String,
        start: String,
        end: String,
        mean: Int,
        stddev: Int,
        n: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO transit_travel_time_calibration (transit_line_id, board_stop_id, alight_stop_id, day_type,
                                                         time_band_start, time_band_end, mean_sec, stddev_sec,
                                                         sample_count)
            VALUES (?, ?, ?, ?::day_type, ?::time, ?::time, ?, ?, ?)
            """.trimIndent(),
            lineId,
            boardId,
            alightId,
            dayType,
            start,
            end,
            mean,
            stddev,
            n,
        )
    }

    private fun getCalibration(routeId: Long): ResultActionsDsl =
        mockMvc.get("/api/v1/commute-routes/$routeId/calibration") {
            header(ApiTokenFilter.HEADER, properties.apiToken)
        }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
