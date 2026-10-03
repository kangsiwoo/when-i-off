package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import kotlin.test.assertEquals

/** `DELETE /commute-trips/{id}` (#88): 앱에서 취소한 trip과 딸린 기록을 지운다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripDeleteApiIT {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute(
            "TRUNCATE recommendation_evaluations, departure_recommendations, walking_segments, gps_traces, " +
                "boarding_attempts, commute_trips, route_legs, commute_routes RESTART IDENTITY CASCADE",
        )
        jdbcTemplate.update("DELETE FROM users WHERE id <> ?", DefaultUser.ID)
    }

    @Test
    fun `deletes the trip with its attempts, walking segments, evaluations and gps points`() {
        val routeId = insertRoute(DefaultUser.ID)
        val walkLegId = insertLeg(routeId, 1)
        val transitLegId = insertLeg(routeId, 2)
        val tripId = insertTrip(routeId, DefaultUser.ID, "2026-09-20T22:30:00Z")
        val keptTripId = insertTrip(routeId, DefaultUser.ID, "2026-09-21T22:30:00Z")
        for (id in listOf(tripId, keptTripId)) {
            insertDependents(id, routeId, walkLegId, transitLegId)
        }
        insertGps(DefaultUser.ID, null, "2026-09-20T12:00:00Z") // 상시 수집분은 건드리지 않는다

        delete(tripId).andExpect { status { isNoContent() } }

        assertEquals(0, count("commute_trips", "id", tripId))
        for (table in listOf("boarding_attempts", "walking_segments", "recommendation_evaluations", "gps_traces")) {
            assertEquals(0, count(table, "commute_trip_id", tripId), table)
            assertEquals(1, count(table, "commute_trip_id", keptTripId), table)
        }
        // SET NULL로 "상시 수집분"이 되어 남지 않았다
        assertEquals(1, countWhere("gps_traces", "commute_trip_id IS NULL"))
        // 추천은 trip의 파생이 아니다 — 평가만 지워지고 추천은 남는다
        assertEquals(2, countWhere("departure_recommendations", "TRUE"))
    }

    @Test
    fun `deleting again, an unknown trip or another user's trip gives 404`() {
        val tripId = insertTrip(insertRoute(DefaultUser.ID), DefaultUser.ID, "2026-09-20T22:30:00Z")
        delete(tripId).andExpect { status { isNoContent() } }
        // 재전송: 이미 지웠다. 클라이언트는 404를 성공으로 본다
        delete(tripId).andExpect { status { isNotFound() } }
        delete(999_999).andExpect { status { isNotFound() } }

        val otherUser =
            jdbcTemplate.queryForObject(
                "INSERT INTO users (email, display_name) VALUES ('other@when-i-off.local', 'other') RETURNING id",
                Long::class.java,
            )!!
        val otherTrip = insertTrip(insertRoute(otherUser), otherUser, "2026-09-20T22:30:00Z")
        insertGps(otherUser, otherTrip, "2026-09-20T22:31:00Z")
        delete(otherTrip).andExpect { status { isNotFound() } }
        assertEquals(1, count("commute_trips", "id", otherTrip))
        assertEquals(1, count("gps_traces", "commute_trip_id", otherTrip))
    }

    @Test
    fun `a trip that already arrived can also be deleted`() {
        val tripId = insertTrip(insertRoute(DefaultUser.ID), DefaultUser.ID, "2026-09-20T22:30:00Z")
        jdbcTemplate.update(
            "UPDATE commute_trips SET arrived_destination_at = '2026-09-20T23:10:00Z' WHERE id = ?",
            tripId,
        )
        delete(tripId).andExpect { status { isNoContent() } }
        assertEquals(0, count("commute_trips", "id", tripId))
    }

    @Test
    fun `requires the api token`() {
        val tripId = insertTrip(insertRoute(DefaultUser.ID), DefaultUser.ID, "2026-09-20T22:30:00Z")
        mockMvc.delete("/api/v1/commute-trips/$tripId").andExpect { status { isUnauthorized() } }
        assertEquals(1, count("commute_trips", "id", tripId))
    }

    // --- fixtures ---

    private fun delete(tripId: Long): ResultActionsDsl =
        mockMvc.delete("/api/v1/commute-trips/$tripId") {
            header(ApiTokenFilter.HEADER, properties.apiToken)
        }

    private fun count(
        table: String,
        column: String,
        id: Long,
    ): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Int::class.java, id)!!

    private fun countWhere(
        table: String,
        condition: String,
    ): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE $condition", Int::class.java)!!

    private fun insertRoute(userId: Long): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_routes (user_id, name, direction, origin_lat, origin_lng, destination_lat, destination_lng)
            VALUES (?, '출근', 'TO_WORK', 37.2, 127.07, 37.49, 127.1) RETURNING id
            """.trimIndent(),
            Long::class.java,
            userId,
        )!!

    private fun insertLeg(
        routeId: Long,
        seq: Int,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO route_legs (commute_route_id, seq_order, leg_type, start_lat, start_lng, end_lat, end_lng)
            VALUES (?, ?, 'WALK', 37.2, 127.07, 37.2, 127.09) RETURNING id
            """.trimIndent(),
            Long::class.java,
            routeId,
            seq,
        )!!

    private fun insertTrip(
        routeId: Long,
        userId: Long,
        leftHomeAt: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO commute_trips (user_id, commute_route_id, trip_date, left_home_at)
            VALUES (?, ?, (?::timestamptz AT TIME ZONE 'Asia/Seoul')::date, ?::timestamptz) RETURNING id
            """.trimIndent(),
            Long::class.java,
            userId,
            routeId,
            leftHomeAt,
            leftHomeAt,
        )!!

    private fun insertGps(
        userId: Long,
        tripId: Long?,
        recordedAt: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO gps_traces (user_id, commute_trip_id, recorded_at, lat, lng) VALUES (?, ?, ?::timestamptz, 37.2, 127.07)",
            userId,
            tripId,
            recordedAt,
        )
    }

    /** trip 하나에 딸린 행을 테이블마다 하나씩: 탑승 시도, 도보 구간, 추천 평가(와 그 추천), GPS 점. */
    private fun insertDependents(
        tripId: Long,
        routeId: Long,
        walkLegId: Long,
        attemptLegId: Long,
    ) {
        val (tripDate, leftHomeAt) =
            jdbcTemplate.queryForObject(
                "SELECT trip_date::text, left_home_at FROM commute_trips WHERE id = ?",
                { rs, _ -> rs.getString(1) to rs.getObject(2, java.time.OffsetDateTime::class.java) },
                tripId,
            )!!
        // 스키마상 탑승 시도는 TRANSIT 구간 검사가 API에만 있다. 여기서는 FK 동작만 본다.
        jdbcTemplate.update(
            "INSERT INTO boarding_attempts (commute_trip_id, route_leg_id, arrived_at_stop_at) VALUES (?, ?, ?)",
            tripId,
            attemptLegId,
            leftHomeAt.plusMinutes(6),
        )
        jdbcTemplate.update(
            """
            INSERT INTO walking_segments (commute_trip_id, route_leg_id, started_at, ended_at, duration_sec,
                                          distance_m, avg_speed_mps)
            VALUES (?, ?, ?, ?, 360, 470, 1.3)
            """.trimIndent(),
            tripId,
            walkLegId,
            leftHomeAt,
            leftHomeAt.plusMinutes(6),
        )
        val recommendationId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO departure_recommendations (user_id, commute_route_id, target_date, target_arrival_at,
                    recommended_leave_home_at, catch_probability, buffer_seconds, model_version)
                VALUES (?, ?, ?::date, ?, ?, 0.9, 600, 'v2') RETURNING id
                """.trimIndent(),
                Long::class.java,
                DefaultUser.ID,
                routeId,
                tripDate,
                leftHomeAt.plusMinutes(40),
                leftHomeAt,
            )!!
        jdbcTemplate.update(
            """
            INSERT INTO recommendation_evaluations (commute_route_id, target_date, model_version,
                departure_recommendation_id, commute_trip_id, target_arrival_at, recommended_leave_home_at,
                all_legs_caught, missed_count)
            VALUES (?, ?::date, 'v2', ?, ?, ?, ?, TRUE, 0)
            """.trimIndent(),
            routeId,
            tripDate,
            recommendationId,
            tripId,
            leftHomeAt.plusMinutes(40),
            leftHomeAt,
        )
        insertGps(DefaultUser.ID, tripId, leftHomeAt.plusMinutes(1).toString())
    }
}
