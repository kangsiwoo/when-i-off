package com.kangsiwoo.whenioff.trip.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.common.domain.DayType
import com.kangsiwoo.whenioff.route.domain.CommuteDirection
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservation
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLineStop
import com.kangsiwoo.whenioff.transit.domain.TransitLineStopRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitSchedule
import com.kangsiwoo.whenioff.transit.domain.TransitScheduleRepository
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 탑승 시도의 `vehicleScheduledOrPredictedAt`을 서버가 채우는 규칙 (#54).
 * 기준 시각은 2026-09-21(월) 07:36 KST. 시간표는 평일 UP 07:38·07:50, 반대 방향(DOWN) 07:37.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BoardingAttemptSnapshotApiTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var commuteRouteRepository: CommuteRouteRepository

    @Autowired lateinit var routeLegRepository: RouteLegRepository

    @Autowired lateinit var transitLineRepository: TransitLineRepository

    @Autowired lateinit var transitStopRepository: TransitStopRepository

    @Autowired lateinit var lineStopRepository: TransitLineStopRepository

    @Autowired lateinit var scheduleRepository: TransitScheduleRepository

    @Autowired lateinit var observationRepository: TransitArrivalObservationRepository

    private lateinit var route: CommuteRoute
    private lateinit var transitLeg: RouteLeg
    private lateinit var line: TransitLine
    private lateinit var boardStop: TransitStop

    @BeforeEach
    fun seed() {
        jdbcTemplate.execute(
            "TRUNCATE gps_traces, boarding_attempts, commute_trips, route_legs, commute_routes, " +
                "transit_stops, transit_lines RESTART IDENTITY CASCADE",
        )
        route =
            commuteRouteRepository.save(
                CommuteRoute(
                    user = userRepository.getReferenceById(DefaultUser.ID),
                    name = "출근",
                    direction = CommuteDirection.TO_WORK,
                    originLat = 37.39,
                    originLng = 127.11,
                    destinationLat = 37.50,
                    destinationLng = 127.03,
                ),
            )
        line = transitLineRepository.save(TransitLine(mode = TransitMode.BUS, name = "M4403"))
        boardStop = transitStopRepository.save(TransitStop(TransitMode.BUS, "집앞", 37.391, 127.111))
        val alightStop = transitStopRepository.save(TransitStop(TransitMode.BUS, "회사앞", 37.499, 127.031))
        // 집앞은 양방향 모두에 있다. UP에서만 회사앞이 뒤에 온다 → 구간 방향은 UP.
        lineStopRepository.saveAll(
            listOf(
                TransitLineStop(line, boardStop, UP, 1),
                TransitLineStop(line, alightStop, UP, 5),
                TransitLineStop(line, alightStop, DOWN, 1),
                TransitLineStop(line, boardStop, DOWN, 5),
            ),
        )
        transitLeg =
            routeLegRepository.save(
                RouteLeg(
                    commuteRoute = route,
                    seqOrder = 1,
                    legType = LegType.TRANSIT,
                    transitLine = line,
                    boardStop = boardStop,
                    alightStop = alightStop,
                    plannedTravelSec = 2400,
                ),
            )
    }

    @Test
    fun `fills from the earliest prediction at or after the reference in the latest batch within 2 minutes`() {
        seedSchedules()
        // 더 오래된 묶음은 더 이른 예측을 갖고 있어도 쓰지 않는다.
        observe(REF.minusSeconds(110), REF.plusSeconds(60))
        // 최근 묶음: 이미 지나간 차(기준 이전)는 빼고, 남은 것 중 가장 이른 차.
        observe(REF.minusSeconds(50), REF.minusSeconds(10), REF.plusSeconds(600), REF.plusSeconds(180))

        val attempt = upsert(createTrip(), mapOf("arrivedAtStopAt" to REF.toString()))

        assertEquals(REF.plusSeconds(180).toString(), attempt["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `an observation exactly 2 minutes old still counts`() {
        observe(REF.minusSeconds(120), REF.plusSeconds(240))

        val attempt = upsert(createTrip(), mapOf("arrivedAtStopAt" to REF.toString()))

        assertEquals(REF.plusSeconds(240).toString(), attempt["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `an observation older than 2 minutes is ignored and the schedule in the leg direction is used`() {
        seedSchedules()
        observe(REF.minusSeconds(121), REF.plusSeconds(60))

        val attempt = upsert(createTrip(), mapOf("arrivedAtStopAt" to REF.toString()))

        // DOWN의 07:37이 아니라 UP의 07:38.
        assertEquals(kst("07:38"), attempt["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `an observation made after the reference is ignored`() {
        seedSchedules()
        observe(REF.plusSeconds(10), REF.plusSeconds(60))

        val attempt = upsert(createTrip(), mapOf("arrivedAtStopAt" to REF.toString()))

        assertEquals(kst("07:38"), attempt["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `a latest batch with only past predictions falls back to the schedule`() {
        seedSchedules()
        observe(REF.minusSeconds(100), REF.plusSeconds(60))
        observe(REF.minusSeconds(30), REF.minusSeconds(5))

        val attempt = upsert(createTrip(), mapOf("arrivedAtStopAt" to REF.toString()))

        assertEquals(kst("07:38"), attempt["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `stays null without an observation or a schedule`() {
        val attempt = upsert(createTrip(), mapOf("arrivedAtStopAt" to REF.toString()))

        assertNull(attempt["vehicleScheduledOrPredictedAt"]) // null 필드는 응답에서 빠진다
    }

    @Test
    fun `the value sent by the app wins`() {
        seedSchedules()
        observe(REF.minusSeconds(30), REF.plusSeconds(180))
        val sent = REF.plusSeconds(400).toString()

        val attempt =
            upsert(createTrip(), mapOf("arrivedAtStopAt" to REF.toString(), "vehicleScheduledOrPredictedAt" to sent))

        assertEquals(sent, attempt["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `a filled value is a snapshot - resend and patch do not change it`() {
        seedSchedules()
        val tripId = createTrip()
        val first = upsert(tripId, mapOf("arrivedAtStopAt" to REF.toString()))
        assertEquals(kst("07:38"), first["vehicleScheduledOrPredictedAt"].asText())

        // 이제는 다른 답을 낼 관측이 생겼고, 기준 시각도 바뀐다. 그래도 한 번 채운 값은 그대로다.
        val later = REF.plusSeconds(60)
        observe(later.minusSeconds(10), later.plusSeconds(30))
        val resent = upsert(tripId, mapOf("arrivedAtStopAt" to later.toString()), expectCreated = false)
        assertEquals(kst("07:38"), resent["vehicleScheduledOrPredictedAt"].asText())

        val patched =
            patchJson(
                "/api/v1/boarding-attempts/${first["id"].asLong()}",
                mapOf("arrivedAtStopAt" to later.toString(), "result" to "CAUGHT"),
            ).andExpect { status { isOk() } }.json()
        assertEquals(kst("07:38"), patched["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `a later attempt uses the departure of the previous attempt as the reference`() {
        seedSchedules()
        val tripId = createTrip()
        val departed = kstInstant("07:38:20")
        upsert(
            tripId,
            mapOf(
                "arrivedAtStopAt" to REF.toString(),
                "vehicleActualDepartureAt" to departed.toString(),
                "result" to "MISSED",
            ),
        )
        // 도착(07:36)이 기준이면 07:35:40 관측의 07:37:40 차가 잡힌다. 앞 차가 떠난 07:38:20이 기준이라
        // 그 관측은 2분을 넘겼고, 시간표에서 07:38:20 이후 첫 차인 07:50이 된다.
        observe(REF.minusSeconds(20), REF.plusSeconds(100))

        val second = upsert(tripId, mapOf("attemptSeq" to 2, "arrivedAtStopAt" to REF.toString()))

        assertEquals(kst("07:50"), second["vehicleScheduledOrPredictedAt"].asText())
    }

    @Test
    fun `a later attempt waits until the previous departure arrives`() {
        seedSchedules()
        val tripId = createTrip()
        val first = upsert(tripId, mapOf("arrivedAtStopAt" to REF.toString()))
        val second = upsert(tripId, mapOf("attemptSeq" to 2))
        assertNull(second["vehicleScheduledOrPredictedAt"]) // null 필드는 응답에서 빠진다

        // 앞 시도의 출발이 PATCH로 들어오면 그것이 2번째 시도의 기준 시각이 된다.
        patchJson(
            "/api/v1/boarding-attempts/${first["id"].asLong()}",
            mapOf("vehicleActualDepartureAt" to kstInstant("07:38:20").toString(), "result" to "MISSED"),
        ).andExpect { status { isOk() } }

        assertEquals(kstInstant("07:50"), storedSnapshot(second["id"].asLong()))
    }

    @Test
    fun `a reference added later by patch triggers the fill`() {
        seedSchedules()
        val tripId = createTrip()
        val attempt = upsert(tripId, mapOf("result" to "UNKNOWN"))
        assertNull(attempt["vehicleScheduledOrPredictedAt"]) // null 필드는 응답에서 빠진다

        val patched =
            patchJson("/api/v1/boarding-attempts/${attempt["id"].asLong()}", mapOf("arrivedAtStopAt" to REF.toString()))
                .andExpect { status { isOk() } }
                .json()

        assertEquals(kst("07:38"), patched["vehicleScheduledOrPredictedAt"].asText())
    }

    private fun seedSchedules() {
        scheduleRepository.saveAll(
            listOf(
                TransitSchedule(line, boardStop, DayType.WEEKDAY, UP, LocalTime.parse("07:30")),
                TransitSchedule(line, boardStop, DayType.WEEKDAY, UP, LocalTime.parse("07:38")),
                TransitSchedule(line, boardStop, DayType.WEEKDAY, UP, LocalTime.parse("07:50")),
                TransitSchedule(line, boardStop, DayType.WEEKDAY, DOWN, LocalTime.parse("07:37")),
            ),
        )
    }

    /** 폴러처럼 한 번의 조회(같은 `observedAt`)에 차량 여러 대의 예측을 적재한다. */
    private fun observe(
        observedAt: Instant,
        vararg predicted: Instant,
    ) {
        observationRepository.saveAll(
            predicted.map {
                TransitArrivalObservation(
                    transitLine = line,
                    stop = boardStop,
                    observedAt = observedAt,
                    predictedArrivalAt = it,
                    source = "TAGO_ARVL",
                )
            },
        )
    }

    private fun storedSnapshot(attemptId: Long): Instant? {
        val stored =
            jdbcTemplate.queryForObject(
                "SELECT vehicle_scheduled_or_predicted_at FROM boarding_attempts WHERE id = ?",
                java.sql.Timestamp::class.java,
                attemptId,
            )
        return stored?.toInstant()
    }

    private fun createTrip(): Long =
        postJson("/api/v1/commute-trips", mapOf("routeId" to route.id, "tripDate" to "2026-09-21"))
            .andExpect { status { isCreated() } }
            .json()["id"]
            .asLong()

    private fun upsert(
        tripId: Long,
        fields: Map<String, Any>,
        expectCreated: Boolean = true,
    ): JsonNode =
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to transitLeg.id) + fields)
            .andExpect { status { if (expectCreated) isCreated() else isOk() } }
            .json()

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
        const val UP = "UP"
        const val DOWN = "DOWN"

        /** 2026-09-21(월) 07:36 KST. */
        val REF: Instant = Instant.parse("2026-09-20T22:36:00Z")

        fun kstInstant(time: String): Instant =
            java.time.LocalDate
                .parse("2026-09-21")
                .atTime(LocalTime.parse(time))
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toInstant()

        fun kst(time: String): String = kstInstant(time).toString()
    }
}
