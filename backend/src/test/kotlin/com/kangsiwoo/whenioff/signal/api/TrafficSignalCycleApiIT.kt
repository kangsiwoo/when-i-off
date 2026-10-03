package com.kangsiwoo.whenioff.signal.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.common.domain.DayType
import com.kangsiwoo.whenioff.signal.domain.SignalDataSource
import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalCycle
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalCycleRepository
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalTime
import kotlin.test.assertEquals

/** 교차로별 신호 주기 조회와 `USER_OBSERVED` 교체 (#78). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrafficSignalCycleApiIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val jdbcTemplate: JdbcTemplate,
        private val properties: WioProperties,
        private val signalRepository: TrafficSignalRepository,
        private val cycleRepository: TrafficSignalCycleRepository,
    ) {
        private lateinit var signal: TrafficSignal

        @BeforeEach
        fun setUp() {
            jdbcTemplate.execute("TRUNCATE traffic_signals CASCADE")
            signal = signalRepository.save(TrafficSignal(lat = 37.4870, lng = 127.1010, name = "수서역 사거리"))
        }

        @Test
        fun `put replaces only user observed rows and get lists every source in order`() {
            // 다른 출처 행: 공공 API 평일 07~10시, 기본 가정 평일 하루 종일. 사용자 행과 시간대가 겹친다.
            seed(SignalDataSource.PUBLIC_API, DayType.WEEKDAY, "07:00", "10:00", 140, 100)
            seed(SignalDataSource.DEFAULT_ASSUMPTION, DayType.WEEKDAY, "00:00", "23:59:59", 120, 90)
            seed(SignalDataSource.USER_OBSERVED, DayType.SATURDAY, "09:00", "18:00", 100, 60)

            val body =
                listOf(
                    // 순서를 섞어 보낸다. 응답은 day_type → 시작 → 출처 우선순위 순이어야 한다.
                    cycle("SUNDAY_HOLIDAY", "10:00", "20:00", 90, 50),
                    cycle("WEEKDAY", "07:00", "09:30", 150, 110),
                    cycle("WEEKDAY", "09:30", "12:00", 120, 70),
                )
            val replaced = putCycles(body).andExpect(status().isOk).json()
            val listed = getCycles()
            assertEquals(replaced, listed)

            assertEquals(
                listOf(
                    "WEEKDAY 00:00:00 DEFAULT_ASSUMPTION",
                    "WEEKDAY 07:00:00 USER_OBSERVED",
                    "WEEKDAY 07:00:00 PUBLIC_API",
                    "WEEKDAY 09:30:00 USER_OBSERVED",
                    "SUNDAY_HOLIDAY 10:00:00 USER_OBSERVED",
                ),
                listed.map { "${it["dayType"].asText()} ${it["timeBandStart"].asText()} ${it["source"].asText()}" },
            )
            // 예전 토요일 USER_OBSERVED 행은 교체로 사라지고, 다른 출처 행은 그대로.
            assertEquals(2, rows(SignalDataSource.PUBLIC_API) + rows(SignalDataSource.DEFAULT_ASSUMPTION))
            assertEquals(3, rows(SignalDataSource.USER_OBSERVED))
            val first = listed[1]
            assertEquals("09:30:00", first["timeBandEnd"].asText())
            assertEquals(150, first["cycleDurationSec"].asInt())
            assertEquals(110, first["redDurationSec"].asInt())

            // 빈 배열 = 사용자 행 전부 삭제. 다른 출처는 남는다.
            putCycles(emptyList()).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
            assertEquals(0, rows(SignalDataSource.USER_OBSERVED))
            assertEquals(2, cycleRepository.count())
        }

        @Test
        fun `bands that touch end to start are not overlapping, and seconds are kept`() {
            putCycles(
                listOf(
                    cycle("WEEKDAY", "00:00", "07:00", 120, 90),
                    cycle("WEEKDAY", "07:00", "23:59:59", 120, 60),
                    // 다른 day_type은 같은 시간대여도 된다.
                    cycle("SATURDAY", "00:00", "23:59:59", 30, 29),
                ),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$[1].timeBandEnd").value("23:59:59"))
            assertEquals(3, rows(SignalDataSource.USER_OBSERVED))
        }

        @Test
        fun `invalid rows are rejected with 400 and nothing changes`() {
            putCycles(listOf(cycle("WEEKDAY", "08:00", "09:00", 120, 80))).andExpect(status().isOk)

            val cases =
                listOf(
                    listOf(cycle("WEEKDAY", "08:00", "09:00", 120, 120)) to
                        "cycles[0]: redDurationSec must be greater than 0 and less than cycleDurationSec (120), got 120",
                    listOf(cycle("WEEKDAY", "08:00", "09:00", 120, 0)) to
                        "cycles[0]: redDurationSec must be greater than 0",
                    listOf(cycle("WEEKDAY", "08:00", "09:00", 29, 10)) to
                        "cycles[0]: cycleDurationSec must be between 30 and 300 seconds, got 29",
                    listOf(cycle("WEEKDAY", "08:00", "09:00", 301, 10)) to "between 30 and 300 seconds, got 301",
                    listOf(cycle("WEEKDAY", "09:00", "09:00", 120, 80)) to
                        "cycles[0]: timeBandStart (09:00) must be before timeBandEnd (09:00)",
                    listOf(cycle("WEEKDAY", "22:00", "02:00", 120, 80)) to "a band cannot cross midnight",
                    listOf(
                        cycle("WEEKDAY", "07:00", "09:00", 120, 80),
                        cycle("SATURDAY", "07:00", "09:00", 120, 80),
                        cycle("WEEKDAY", "08:30", "10:00", 120, 80),
                    ) to "cycles[0] and cycles[2] overlap on WEEKDAY (07:00-09:00 and 08:30-10:00)",
                )
            for ((body, detail) in cases) {
                putCycles(body)
                    .andExpect(status().isBadRequest)
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail", containsString(detail)))
            }
            // 거부된 요청은 기존 사용자 행을 지우지 않는다.
            assertEquals(1, rows(SignalDataSource.USER_OBSERVED))

            // 필수 필드 누락·알 수 없는 day_type은 본문 해석 단계의 400.
            mockMvc
                .perform(
                    put("/api/v1/traffic-signals/${signal.id}/cycles")
                        .header("X-Api-Token", properties.apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"cycles":[{"dayType":"HOLIDAY","timeBandStart":"08:00","timeBandEnd":"09:00"}]}""",
                        ),
                ).andExpect(status().isBadRequest)
        }

        @Test
        fun `unknown signal is 404 and the api needs the token`() {
            mockMvc
                .perform(get("/api/v1/traffic-signals/${signal.id!! + 999}/cycles").authed())
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.detail").value("traffic signal ${signal.id!! + 999} not found"))
            mockMvc
                .perform(
                    put("/api/v1/traffic-signals/${signal.id!! + 999}/cycles")
                        .authed()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"cycles":[]}"""),
                ).andExpect(status().isNotFound)
            mockMvc
                .perform(get("/api/v1/traffic-signals/${signal.id}/cycles"))
                .andExpect(status().isUnauthorized)
        }

        private fun seed(
            source: SignalDataSource,
            dayType: DayType,
            start: String,
            end: String,
            cycleSec: Int,
            redSec: Int,
        ) {
            cycleRepository.save(
                TrafficSignalCycle(
                    trafficSignal = signal,
                    dayType = dayType,
                    timeBandStart = LocalTime.parse(start),
                    timeBandEnd = LocalTime.parse(end),
                    redDurationSec = redSec,
                    cycleDurationSec = cycleSec,
                    source = source,
                ),
            )
        }

        private fun rows(source: SignalDataSource): Int =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM traffic_signal_cycles WHERE source = ?::signal_data_source",
                Int::class.java,
                source.name,
            )!!

        private fun cycle(
            dayType: String,
            start: String,
            end: String,
            cycleSec: Int,
            redSec: Int,
        ) = mapOf(
            "dayType" to dayType,
            "timeBandStart" to start,
            "timeBandEnd" to end,
            "cycleDurationSec" to cycleSec,
            "redDurationSec" to redSec,
        )

        private fun putCycles(cycles: List<Map<String, Any>>): ResultActions =
            mockMvc.perform(
                put("/api/v1/traffic-signals/${signal.id}/cycles")
                    .authed()
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("cycles" to cycles))),
            )

        private fun getCycles(): JsonNode =
            mockMvc
                .perform(get("/api/v1/traffic-signals/${signal.id}/cycles").authed())
                .andExpect(status().isOk)
                .json()

        private fun ResultActions.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)

        private fun MockHttpServletRequestBuilder.authed() = header("X-Api-Token", properties.apiToken)
    }
