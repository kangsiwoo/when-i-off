package com.kangsiwoo.whenioff.retention

import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 보관 정책 (#74): 기간 경계, 파생 안 된 trip의 점 보존, 꺼짐, 배치 삭제. */
@SpringBootTest
@ActiveProfiles("test")
class RetentionIT {
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @Autowired lateinit var context: ApplicationContext

    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val meterRegistry = SimpleMeterRegistry()

    private var routeId = 0L
    private var walkLegId = 0L

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute(
            "TRUNCATE gps_traces, walking_segments, boarding_attempts, commute_trips, route_legs, commute_routes, " +
                "transit_arrival_observations, traffic_signal_states, traffic_signals, transit_lines, transit_stops " +
                "RESTART IDENTITY CASCADE",
        )
        routeId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO commute_routes (user_id, name, direction, origin_lat, origin_lng, destination_lat, destination_lng)
                VALUES (?, '출근', 'TO_WORK', 37.2, 127.07, 37.49, 127.1) RETURNING id
                """.trimIndent(),
                Long::class.java,
                DefaultUser.ID,
            )!!
        walkLegId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO route_legs (commute_route_id, seq_order, leg_type, start_lat, start_lng, end_lat, end_lng)
                VALUES (?, 1, 'WALK', 37.2, 127.07, 37.2, 127.09) RETURNING id
                """.trimIndent(),
                Long::class.java,
                routeId,
            )!!
    }

    @Test
    fun `gps points are deleted after 90 days only once their trip is derived`() {
        // 상시 수집분: recorded_at 기준
        insertPoint(null, daysAgo(89))
        insertPoint(null, daysAgo(91))
        // 파생된 trip: trip 시각(left_home_at) 기준. 점 시각과 상관없이 trip 단위로 지운다
        val derivedOld = insertTrip(leftHome = daysAgo(91), derived = true)
        val derivedNew = insertTrip(leftHome = daysAgo(89), derived = true)
        // left_home_at이 없으면 created_at
        val derivedNoLeftHome = insertTrip(leftHome = null, createdAt = daysAgo(91), derived = true)
        // 파생 안 된 trip: 90일이 지나도 남기고, 상한(365일)을 넘으면 지운다
        val underived = insertTrip(leftHome = daysAgo(200), derived = false)
        val underivedAncient = insertTrip(leftHome = daysAgo(366), derived = false)
        val underivedAtCap = insertTrip(leftHome = daysAgo(364), derived = false)
        insertPoint(derivedOld, daysAgo(91).plusSeconds(60))
        insertPoint(derivedOld, daysAgo(91).plusSeconds(90))
        insertPoint(derivedNew, daysAgo(89).plusSeconds(60))
        insertPoint(derivedNoLeftHome, daysAgo(92))
        insertPoint(underived, daysAgo(200))
        insertPoint(underivedAncient, daysAgo(366))
        insertPoint(underivedAtCap, daysAgo(364))

        val result = service().run()

        assertTrue(result.ran)
        assertEquals(5, result.rows(RetentionService.GPS_TRACES))
        assertEquals(listOf(derivedNew, underived, underivedAtCap), remainingTripIds())
        assertEquals(1, count("SELECT count(*) FROM gps_traces WHERE commute_trip_id IS NULL"))
        assertEquals(
            daysAgo(89),
            jdbcTemplate
                .queryForObject(
                    "SELECT recorded_at FROM gps_traces WHERE commute_trip_id IS NULL",
                    java.time.OffsetDateTime::class.java,
                )!!
                .toInstant(),
        )
        assertEquals(3.0, deletedMetric("gps_traces", "derived-trip"))
        assertEquals(1.0, deletedMetric("gps_traces", "underived-trip"))
        assertEquals(1.0, deletedMetric("gps_traces", "no-trip"))
        // trip·도보 구간 자체는 지우지 않는다
        assertEquals(6, count("SELECT count(*) FROM commute_trips"))
        assertEquals(3, count("SELECT count(*) FROM walking_segments"))
    }

    @Test
    fun `observations are kept for a year and signal states for 30 days`() {
        val (lineId, stopId) = insertLineAndStop()
        insertObservation(lineId, stopId, daysAgo(364))
        insertObservation(lineId, stopId, daysAgo(366))
        val signalId = insertSignal()
        insertSignalState(signalId, daysAgo(29))
        insertSignalState(signalId, daysAgo(31))

        val result = service().run()

        assertEquals(1, result.rows(RetentionService.ARRIVAL_OBSERVATIONS))
        assertEquals(1, result.rows(RetentionService.SIGNAL_STATES))
        assertEquals(daysAgo(364), single("SELECT observed_at FROM transit_arrival_observations"))
        assertEquals(daysAgo(29), single("SELECT observed_at FROM traffic_signal_states"))
    }

    @Test
    fun `disabled retention deletes nothing and schedules nothing`() {
        insertPoint(null, daysAgo(400))
        val (lineId, stopId) = insertLineAndStop()
        insertObservation(lineId, stopId, daysAgo(400))

        // test 프로필은 꺼져 있다: 스케줄러 빈이 없다
        assertFalse(properties.retention.enabled)
        assertTrue(context.getBeanNamesForType(RetentionScheduler::class.java).isEmpty())

        // 서비스를 직접 불러도 아무것도 하지 않는다
        val result = service(enabled = false).run()
        assertFalse(result.ran)
        assertEquals(1, count("SELECT count(*) FROM gps_traces"))
        assertEquals(1, count("SELECT count(*) FROM transit_arrival_observations"))
    }

    @Test
    fun `deletes in batches until nothing old is left`() {
        repeat(7) { insertPoint(null, daysAgo(100).plusSeconds(it.toLong())) }
        repeat(3) { insertPoint(null, daysAgo(10).plusSeconds(it.toLong())) }

        val result = service(batchSize = 2).run()

        val rule = result.rules.single { it.table == "gps_traces" && it.rule == "no-trip" }
        assertEquals(7, rule.rows)
        assertEquals(4, rule.batches) // 2 + 2 + 2 + 1
        assertEquals(3, count("SELECT count(*) FROM gps_traces"))
        assertEquals(0, count("SELECT count(*) FROM gps_traces WHERE recorded_at < '${daysAgo(90)}'"))
        assertEquals(7.0, deletedMetric("gps_traces", "no-trip"))

        // 정확히 배치 크기의 배수여도 끝난다 (마지막에 0건 배치 한 번)
        repeat(4) { insertPoint(null, daysAgo(100).plusSeconds(100L + it)) }
        assertEquals(4, service(batchSize = 2).run().rows("gps_traces"))
        assertEquals(3, count("SELECT count(*) FROM gps_traces"))
    }

    @Test
    fun `dry run counts without deleting`() {
        insertPoint(null, daysAgo(91))
        insertPoint(null, daysAgo(89))

        val result = service(dryRun = true).run()

        assertTrue(result.dryRun)
        assertEquals(1, result.rows("gps_traces"))
        assertEquals(2, count("SELECT count(*) FROM gps_traces"))
        assertEquals(0.0, deletedMetric("gps_traces", "no-trip"))
    }

    // --- fixtures ---

    private fun service(
        enabled: Boolean = true,
        batchSize: Int = 5_000,
        dryRun: Boolean = false,
    ): RetentionService =
        RetentionService(
            jdbcTemplate,
            properties.copy(
                retention = WioProperties.Retention(enabled = enabled, batchSize = batchSize, dryRun = dryRun),
            ),
            clock,
            meterRegistry,
        )

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun single(sql: String): Instant =
        jdbcTemplate.queryForObject(sql, java.time.OffsetDateTime::class.java)!!.toInstant()

    private fun remainingTripIds(): List<Long> =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT commute_trip_id FROM gps_traces WHERE commute_trip_id IS NOT NULL ORDER BY 1",
            Long::class.java,
        )

    private fun deletedMetric(
        table: String,
        rule: String,
    ): Double =
        meterRegistry
            .find(RetentionService.METRIC)
            .tags("table", table, "rule", rule)
            .counter()
            ?.count() ?: 0.0

    private fun insertTrip(
        leftHome: Instant?,
        createdAt: Instant = now,
        derived: Boolean,
    ): Long {
        val tripId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO commute_trips (user_id, commute_route_id, trip_date, left_home_at, created_at)
                VALUES (?, ?, '2026-01-01', ?, ?) RETURNING id
                """.trimIndent(),
                Long::class.java,
                DefaultUser.ID,
                routeId,
                leftHome?.atOffset(ZoneOffset.UTC),
                createdAt.atOffset(ZoneOffset.UTC),
            )!!
        if (derived) {
            jdbcTemplate.update(
                """
                INSERT INTO walking_segments (commute_trip_id, route_leg_id, started_at, ended_at, duration_sec,
                                              distance_m, avg_speed_mps)
                VALUES (?, ?, ?, ?, 600, 780, 1.3)
                """.trimIndent(),
                tripId,
                walkLegId,
                createdAt.atOffset(ZoneOffset.UTC),
                createdAt.plusSeconds(600).atOffset(ZoneOffset.UTC),
            )
        }
        return tripId
    }

    private fun insertPoint(
        tripId: Long?,
        recordedAt: Instant,
    ) {
        jdbcTemplate.update(
            "INSERT INTO gps_traces (user_id, commute_trip_id, recorded_at, lat, lng) VALUES (?, ?, ?, 37.2, 127.07)",
            DefaultUser.ID,
            tripId,
            recordedAt.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertLineAndStop(): Pair<Long, Long> {
        val lineId =
            jdbcTemplate.queryForObject(
                "INSERT INTO transit_lines (mode, name) VALUES ('BUS', '1550') RETURNING id",
                Long::class.java,
            )!!
        val stopId =
            jdbcTemplate.queryForObject(
                "INSERT INTO transit_stops (mode, name, lat, lng) VALUES ('BUS', '동탄', 37.2, 127.07) RETURNING id",
                Long::class.java,
            )!!
        return lineId to stopId
    }

    private fun insertObservation(
        lineId: Long,
        stopId: Long,
        observedAt: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO transit_arrival_observations (transit_line_id, stop_id, observed_at, predicted_arrival_at, source)
            VALUES (?, ?, ?, ?, 'TAGO_ARVL')
            """.trimIndent(),
            lineId,
            stopId,
            observedAt.atOffset(ZoneOffset.UTC),
            observedAt.plusSeconds(300).atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertSignal(): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO traffic_signals (lat, lng) VALUES (37.2, 127.07) RETURNING id",
            Long::class.java,
        )!!

    private fun insertSignalState(
        signalId: Long,
        observedAt: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO traffic_signal_states (traffic_signal_id, observed_at, approach_dir, signal_kind, status)
            VALUES (?, ?, 'nt', 'Pd', 'stop-And-Remain')
            """.trimIndent(),
            signalId,
            observedAt.atOffset(ZoneOffset.UTC),
        )
    }
}
