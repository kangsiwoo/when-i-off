package com.kangsiwoo.whenioff.route.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/** 경로별 기본 목표 도착 시각과 대상 day_type (#68). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RouteDefaultTargetApiIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val jdbcTemplate: JdbcTemplate,
        private val properties: WioProperties,
    ) {
        @BeforeEach
        fun cleanUp() {
            jdbcTemplate.execute("TRUNCATE commute_routes CASCADE")
        }

        @Test
        fun `new route defaults to no target time and weekdays only`() {
            val id = create(emptyMap())
            mockMvc
                .perform(get("/api/v1/commute-routes/$id").authed())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.route.defaultTargetArrivalTime").doesNotExist())
                .andExpect(jsonPath("$.route.defaultTargetDayTypes.length()").value(1))
                .andExpect(jsonPath("$.route.defaultTargetDayTypes[0]").value("WEEKDAY"))
        }

        @Test
        fun `create stores target time and day types, list and detail return them sorted`() {
            val id =
                create(
                    mapOf(
                        "defaultTargetArrivalTime" to "09:00",
                        "defaultTargetDayTypes" to listOf("SUNDAY_HOLIDAY", "WEEKDAY"),
                    ),
                )
            mockMvc
                .perform(get("/api/v1/commute-routes").authed())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].defaultTargetArrivalTime").value("09:00:00"))
                .andExpect(jsonPath("$[0].defaultTargetDayTypes[0]").value("WEEKDAY"))
                .andExpect(jsonPath("$[0].defaultTargetDayTypes[1]").value("SUNDAY_HOLIDAY"))
            assertEquals(
                "{WEEKDAY,SUNDAY_HOLIDAY}",
                jdbcTemplate.queryForObject(
                    "SELECT default_target_day_types::text FROM commute_routes WHERE id = ?",
                    String::class.java,
                    id,
                ),
            )
        }

        @Test
        fun `patch changes only the given fields`() {
            val id = create(mapOf("defaultTargetArrivalTime" to "08:30:00"))
            mockMvc
                .perform(patch("/api/v1/commute-routes/$id").json(mapOf("defaultTargetDayTypes" to listOf("SATURDAY"))))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("집→회사"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.defaultTargetArrivalTime").value("08:30:00"))
                .andExpect(jsonPath("$.defaultTargetDayTypes[0]").value("SATURDAY"))

            mockMvc
                .perform(
                    patch("/api/v1/commute-routes/$id")
                        .json(mapOf("name" to "  주말 출근 ", "isActive" to false, "defaultTargetArrivalTime" to "07:45")),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("주말 출근"))
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.defaultTargetArrivalTime").value("07:45:00"))
                .andExpect(jsonPath("$.defaultTargetDayTypes[0]").value("SATURDAY"))
        }

        @Test
        fun `patch clears the target time only with the explicit flag`() {
            val id = create(mapOf("defaultTargetArrivalTime" to "09:00"))
            mockMvc
                .perform(patch("/api/v1/commute-routes/$id").json(mapOf("defaultTargetArrivalTime" to null)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.defaultTargetArrivalTime").value("09:00:00"))
            mockMvc
                .perform(patch("/api/v1/commute-routes/$id").json(mapOf("clearDefaultTargetArrivalTime" to true)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.defaultTargetArrivalTime").doesNotExist())
        }

        @Test
        fun `invalid values are rejected with 400`() {
            mockMvc
                .perform(
                    post("/api/v1/commute-routes").json(
                        routeBody(
                            mapOf(
                                "defaultTargetDayTypes" to emptyList<String>(),
                            ),
                        ),
                    ),
                ).andExpect(status().isBadRequest)
                .andExpect(
                    jsonPath("$.errors", hasItem("defaultTargetDayTypes: size must be between 1 and 2147483647")),
                )
            mockMvc
                .perform(post("/api/v1/commute-routes").json(routeBody(mapOf("defaultTargetArrivalTime" to "25:00"))))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    post("/api/v1/commute-routes").json(
                        routeBody(
                            mapOf(
                                "defaultTargetDayTypes" to listOf("HOLIDAY"),
                            ),
                        ),
                    ),
                ).andExpect(status().isBadRequest)

            val id = create(emptyMap())
            mockMvc
                .perform(patch("/api/v1/commute-routes/$id").json(mapOf("name" to "   ")))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    patch("/api/v1/commute-routes/$id").json(mapOf("defaultTargetDayTypes" to emptyList<String>())),
                ).andExpect(status().isBadRequest)
            mockMvc
                .perform(
                    patch("/api/v1/commute-routes/$id")
                        .json(mapOf("defaultTargetArrivalTime" to "09:00", "clearDefaultTargetArrivalTime" to true)),
                ).andExpect(status().isBadRequest)
        }

        @Test
        fun `patch of a missing route is 404`() {
            mockMvc
                .perform(patch("/api/v1/commute-routes/999999").json(mapOf("isActive" to false)))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `database rejects an empty day type set`() {
            val id = create(emptyMap())
            val rejected =
                runCatching {
                    jdbcTemplate.update("UPDATE commute_routes SET default_target_day_types = '{}' WHERE id = ?", id)
                }
            assertEquals(true, rejected.isFailure)
        }

        private fun create(extra: Map<String, Any?>): Long {
            val response =
                mockMvc
                    .perform(post("/api/v1/commute-routes").json(routeBody(extra)))
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            return objectMapper.readTree(response)["id"].asLong()
        }

        private fun routeBody(extra: Map<String, Any?>): Map<String, Any?> =
            mapOf(
                "name" to "집→회사",
                "direction" to "TO_WORK",
                "originLat" to 37.5665,
                "originLng" to 126.9780,
                "destinationLat" to 37.5875,
                "destinationLng" to 126.9780,
            ) + extra

        private fun MockHttpServletRequestBuilder.authed(): MockHttpServletRequestBuilder =
            header("X-Api-Token", properties.apiToken)

        private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
            authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
    }
