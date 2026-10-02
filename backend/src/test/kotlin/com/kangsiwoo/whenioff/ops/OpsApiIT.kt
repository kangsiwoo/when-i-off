package com.kangsiwoo.whenioff.ops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.metrics.CallOutcome
import com.kangsiwoo.whenioff.external.metrics.ExternalCallMetrics
import com.kangsiwoo.whenioff.external.metrics.ExternalSource
import com.kangsiwoo.whenioff.ops.application.BatchRunTracker
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /admin/ops/external-apis` (#76): 토큰, op별 집계·한도 사용률, 폴링·보관 상태. */
@SpringBootTest(properties = ["wio.tago.daily-limit-overrides.opsProbe=8"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpsApiIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val properties: WioProperties,
        private val metrics: ExternalCallMetrics,
        private val tracker: BatchRunTracker,
    ) {
        private val path = "/api/v1/admin/ops/external-apis"

        @Test
        fun `requires the api token`() {
            mockMvc
                .perform(get(path))
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            mockMvc.perform(get(path).header("X-Api-Token", "wrong")).andExpect(status().isUnauthorized)
        }

        @Test
        fun `aggregates calls failures latency and quota per source and op`() {
            listOf(100L, 200L, 300L).forEach { record("opsProbe", it, CallOutcome.SUCCESS) }
            record("opsProbe", 50, CallOutcome.API_ERROR)
            record("opsProbe", 40, CallOutcome.TIMEOUT)
            record("otherProbe", 10, CallOutcome.SUCCESS)

            val body = fetch()

            val tago = body["sources"].single { it["source"].asText() == "tago" }
            assertFalse(tago["configured"].asBoolean()) // test 프로필에는 키가 없다
            val ops = tago["ops"].map { it["op"].asText() }
            // 이 앱이 부르는 op는 호출이 없어도 보이고, 기록된 다른 op가 뒤에 붙는다
            assertEquals(
                listOf(
                    "getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList",
                    "getRouteNoList",
                    "getRouteAcctoThrghSttnList",
                    "opsProbe",
                    "otherProbe",
                ),
                ops,
            )
            val probe = tago["ops"].single { it["op"].asText() == "opsProbe" }
            for (window in listOf("today", "lastHour")) {
                val w = probe[window]
                assertEquals(5, w["calls"].asLong(), window)
                assertEquals(2, w["failures"].asLong(), window)
                assertEquals(0.4, w["failureRate"].asDouble(), window)
                assertTrue(w["p50Ms"].asLong() in 100L..108L, "p50 ${w["p50Ms"]}")
                assertEquals(300, w["p95Ms"].asLong())
                assertEquals(3, w["outcomes"]["success"].asLong())
                assertEquals(1, w["outcomes"]["apiError"].asLong())
                assertEquals(1, w["outcomes"]["timeout"].asLong())
                assertEquals(0, w["outcomes"]["httpError"].asLong())
            }
            // 한도는 op별 설정값(덮어쓰기 8) → 5/8
            assertEquals(8, probe["quota"]["dailyLimit"].asLong())
            assertEquals(5, probe["quota"]["used"].asLong())
            assertEquals(0.625, probe["quota"]["usageRate"].asDouble())

            val other = tago["ops"].single { it["op"].asText() == "otherProbe" }
            assertEquals(properties.tago.dailyLimit, other["quota"]["dailyLimit"].asLong())
            assertEquals(1000, properties.tago.dailyLimit)

            // 호출이 없는 op: 0건이고 실패율·지연은 빠진다
            val klid = body["sources"].single { it["source"].asText() == "klid" }
            val idle = klid["ops"].single { it["op"].asText() == "crsrd_map_info" }
            assertEquals(0, idle["today"]["calls"].asLong())
            assertFalse(idle["today"].has("failureRate"))
            assertFalse(idle["today"].has("p50Ms"))
            assertEquals(5000, idle["quota"]["dailyLimit"].asLong())
            assertEquals(0.0, idle["quota"]["usageRate"].asDouble())
        }

        @Test
        fun `shows polling and retention status with their last runs`() {
            tracker.pollingSucceeded(
                legsPredicted = 2,
                predictions = 5,
                signalStatesInserted = 0,
                failedCodes = setOf("31240"),
            )
            tracker.retentionSucceeded(
                dryRun = true,
                rows =
                    linkedMapOf(
                        "gps_traces" to 3L,
                        "traffic_signal_states" to 0L,
                    ),
            )

            val body = fetch()

            val polling = body["polling"]
            assertFalse(polling["enabled"].asBoolean())
            assertEquals(60_000, polling["intervalMs"].asLong())
            assertEquals(listOf("06:30-09:30", "17:30-20:30"), polling["windows"].map { it.asText() })
            assertTrue(polling.has("withinWindow"))
            assertEquals("SUCCESS", polling["lastRun"]["result"].asText())
            assertEquals(5, polling["lastRun"]["predictions"].asInt())
            assertEquals("31240", polling["lastRun"]["failedCodes"][0].asText())

            val retention = body["retention"]
            assertFalse(retention["enabled"].asBoolean())
            assertFalse(retention.has("nextRunAt"))
            assertEquals("0 30 4 * * *", retention["cron"].asText())
            assertTrue(retention["lastRun"]["dryRun"].asBoolean())
            assertEquals("gps_traces", retention["lastRun"]["rows"][0]["table"].asText())
            assertEquals(3, retention["lastRun"]["rows"][0]["rows"].asLong())

            tracker.pollingFailed(IllegalStateException("db down"))
            val failed = fetch()["polling"]["lastRun"]
            assertEquals("FAILURE", failed["result"].asText())
            assertEquals("IllegalStateException: db down", failed["error"].asText())
        }

        private fun record(
            op: String,
            ms: Long,
            outcome: CallOutcome,
        ) = metrics.record(ExternalSource.TAGO, op, Duration.ofMillis(ms), outcome)

        private fun fetch(): JsonNode {
            val json =
                mockMvc
                    .perform(get(path).header("X-Api-Token", properties.apiToken))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            return objectMapper.readTree(json)
        }
    }
