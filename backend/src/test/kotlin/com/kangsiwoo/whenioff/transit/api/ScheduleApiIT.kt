package com.kangsiwoo.whenioff.transit.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.common.domain.DayType
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitScheduleRepository
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

/** 정적 시간표 CSV 적재와 "다음 N대" 조회. 실시간 API가 없는 GTX 구간을 가정한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleApiIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val jdbcTemplate: JdbcTemplate,
        private val properties: WioProperties,
        private val lineRepository: TransitLineRepository,
        private val stopRepository: TransitStopRepository,
        private val scheduleRepository: TransitScheduleRepository,
    ) {
        private var lineId = 0L
        private var stopId = 0L

        @BeforeEach
        fun setUp() {
            jdbcTemplate.execute("TRUNCATE transit_lines, transit_stops CASCADE")
            lineId =
                lineRepository
                    .save(TransitLine(TransitMode.GTX, "GTX-A", hasRealtimeApi = false))
                    .id!!
            stopId = stopRepository.save(TransitStop(TransitMode.GTX, "동탄", 37.2003, 127.0980)).id!!
        }

        @Test
        fun `import is idempotent and a revised file drops removed trips`() {
            val counts = import(fullCsv())
            assertEquals(listOf(5, 5, 0, 0), counts)
            assertEquals(5, scheduleRepository.count())

            // 같은 파일을 다시 넣으면 전부 updated, 행 수는 그대로.
            assertEquals(listOf(5, 0, 5, 0), import(fullCsv()))
            assertEquals(5, scheduleRepository.count())

            // 개정으로 평일 차편이 하나로 줄면 그 조합만 줄고, 파일에 없는 조합은 남는다.
            assertEquals(listOf(1, 0, 1, 0), import(csv("$lineId,$stopId,WEEKDAY,23:30")))
            assertEquals(1, weekdayRows())
            assertEquals(4, scheduleRepository.count())
        }

        @Test
        fun `next departures continue into saturday after the last friday trip`() {
            import(fullCsv())

            // 금요일 23:20 KST. 남은 평일 두 편 뒤에 토요일 첫차가 이어져야 한다.
            val response = next(Instant.parse("2026-05-01T14:20:00Z"), limit = 3)

            assertEquals(
                listOf(DayType.WEEKDAY, DayType.WEEKDAY, DayType.SATURDAY),
                response.departures.map { it.dayType },
            )
            assertEquals(
                listOf(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-02")),
                response.departures.map { it.serviceDate },
            )
            assertEquals(
                listOf(
                    Instant.parse("2026-05-01T14:30:00Z"),
                    Instant.parse("2026-05-01T14:55:00Z"),
                    Instant.parse("2026-05-01T20:30:00Z"),
                ),
                response.departures.map { it.departureAt },
            )
            assertEquals(LocalTime.parse("05:30"), response.departures.last().scheduledTime)
        }

        @Test
        fun `next departures use the holiday timetable on a holiday eve`() {
            import(fullCsv())

            // 월요일 23:50 KST, 다음 날은 어린이날이라 SUNDAY_HOLIDAY 시간표로 넘어간다.
            val response = next(Instant.parse("2026-05-04T14:50:00Z"), limit = 2)

            assertEquals(listOf(DayType.WEEKDAY, DayType.SUNDAY_HOLIDAY), response.departures.map { it.dayType })
            assertEquals(
                listOf(Instant.parse("2026-05-04T14:55:00Z"), Instant.parse("2026-05-04T21:20:00Z")),
                response.departures.map { it.departureAt },
            )
        }

        @Test
        fun `rejects unknown ids and malformed rows with the row number`() {
            importRequest(csv("$lineId,$stopId,WEEKDAY,23:30", "$lineId,999999,WEEKDAY,23:40"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("row 3: unknown transit_stop_id 999999")))

            importRequest(csv("$lineId,$stopId,HOLIDAY,23:30"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("row 2: unknown day_type 'HOLIDAY'")))

            importRequest(csv("$lineId,$stopId,WEEKDAY,25:99"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("row 2: scheduled_time '25:99'")))

            importRequest(csv("$lineId,$stopId,WEEKDAY"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("row 2: expected 4 columns")))

            assertEquals(0, scheduleRepository.count())
        }

        /** 헤더가 없는 파일의 깨진 첫 행이 헤더로 오인돼 조용히 버려지면 안 된다. */
        @Test
        fun `a malformed first row is reported, not mistaken for a header`() {
            mockMvc
                .perform(
                    multipart("/api/v1/admin/schedules/import")
                        .file(
                            MockMultipartFile("file", "s.csv", "text/csv", "oops,$stopId,WEEKDAY,08:00".toByteArray()),
                        ).header(ApiTokenFilter.HEADER, properties.apiToken),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("row 1: transit_line_id must be a number")))

            assertEquals(0, scheduleRepository.count())
        }

        @Test
        fun `query rejects unknown line or stop and a bad limit`() {
            mockMvc
                .perform(get("/api/v1/transit-lines/999999/schedules/next?stopId=$stopId").authed())
                .andExpect(status().isNotFound)
            mockMvc
                .perform(get("/api/v1/transit-lines/$lineId/schedules/next?stopId=999999").authed())
                .andExpect(status().isNotFound)
            mockMvc
                .perform(get("/api/v1/transit-lines/$lineId/schedules/next?stopId=$stopId&limit=0").authed())
                .andExpect(status().isBadRequest)
        }

        private fun weekdayRows() =
            scheduleRepository.findByTransitLineIdAndStopIdAndDayType(lineId, stopId, DayType.WEEKDAY).size

        private fun fullCsv() =
            csv(
                "$lineId,$stopId,WEEKDAY,23:30",
                "$lineId,$stopId,WEEKDAY,23:55",
                "$lineId,$stopId,SATURDAY,05:30",
                "$lineId,$stopId,SATURDAY,06:00",
                "$lineId,$stopId,SUNDAY_HOLIDAY,06:20",
            )

        /** 엑셀에서 내보낸 CSV처럼 BOM으로 시작시킨다. */
        private fun csv(vararg rows: String) =
            "${Char(0xFEFF)}" +
                (listOf("transit_line_id,transit_stop_id,day_type,scheduled_time") + rows).joinToString("\n")

        /** `{fetched, created, updated, skipped}` 순서로 돌려준다. */
        private fun import(body: String): List<Int> {
            val json =
                importRequest(body)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            val node = objectMapper.readTree(json)
            return listOf("fetched", "created", "updated", "skipped").map { node[it].asInt() }
        }

        private fun importRequest(body: String) =
            mockMvc.perform(
                multipart("/api/v1/admin/schedules/import")
                    .file(MockMultipartFile("file", "schedules.csv", "text/csv", body.toByteArray()))
                    .header(ApiTokenFilter.HEADER, properties.apiToken),
            )

        private fun next(
            at: Instant,
            limit: Int,
        ): NextDeparturesResponse {
            val url = "/api/v1/transit-lines/$lineId/schedules/next?stopId=$stopId&at=$at&limit=$limit"
            val json =
                mockMvc
                    .perform(get(url).authed())
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            return objectMapper.readValue(json, NextDeparturesResponse::class.java)
        }

        private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.authed() =
            header(ApiTokenFilter.HEADER, properties.apiToken)
    }
