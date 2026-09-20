package com.kangsiwoo.whenioff.route.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RouteApiIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val jdbcTemplate: JdbcTemplate,
        private val properties: WioProperties,
    ) {
        private val token get() = properties.apiToken

        // 서울시청 근처. 정류장/신호등 좌표는 여기서 동/북으로 조금씩 떨어진 지점을 쓴다.
        private val baseLat = 37.5665
        private val baseLng = 126.9780

        @BeforeEach
        fun cleanUp() {
            jdbcTemplate.execute("TRUNCATE commute_routes, transit_lines, transit_stops, traffic_signals CASCADE")
        }

        @Test
        fun `rejects requests without api token`() {
            mockMvc
                .perform(get("/api/v1/commute-routes"))
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        }

        @Test
        fun `create route, replace legs, then detail shows legs in order`() {
            val lineId = createLine("BUS", "1234", "L-1")
            val boardId = createStop("BUS", "board", baseLat + 0.002, baseLng)
            val alightId = createStop("BUS", "alight", baseLat + 0.02, baseLng)
            val routeId = createRoute()

            mockMvc
                .perform(get("/api/v1/commute-routes").authed())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(routeId))

            val legs =
                listOf(
                    walk(1, baseLat, baseLng, baseLat + 0.002, baseLng),
                    transit(2, lineId, boardId, alightId),
                    walk(3, baseLat + 0.02, baseLng, baseLat + 0.021, baseLng),
                )
            mockMvc
                .perform(put("/api/v1/commute-routes/$routeId/legs").json(mapOf("legs" to legs.reversed())))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.legs.length()").value(3))
                .andExpect(jsonPath("$.legs[1].legType").value("TRANSIT"))

            // 전체 교체가 idempotent한지: 같은 seq_order로 다시 PUT해도 UNIQUE 충돌이 없어야 한다.
            mockMvc
                .perform(put("/api/v1/commute-routes/$routeId/legs").json(mapOf("legs" to legs)))
                .andExpect(status().isOk)

            val detail = getJson("/api/v1/commute-routes/$routeId")
            assertEquals(routeId, detail["route"]["id"].asLong())
            assertEquals(listOf(1, 2, 3), detail["legs"].map { it["seqOrder"].asInt() })
            assertEquals(listOf("WALK", "TRANSIT", "WALK"), detail["legs"].map { it["legType"].asText() })
            assertEquals(lineId, detail["legs"][1]["transitLineId"].asLong())
            assertEquals(boardId, detail["legs"][1]["boardStopId"].asLong())
            assertEquals(alightId, detail["legs"][1]["alightStopId"].asLong())
            assertEquals(baseLat, detail["legs"][0]["startLat"].asDouble())
        }

        @Test
        fun `non-alternating legs are rejected with 400`() {
            val routeId = createRoute()
            val legs =
                listOf(
                    walk(1, baseLat, baseLng, baseLat + 0.001, baseLng),
                    walk(2, baseLat + 0.001, baseLng, baseLat + 0.002, baseLng),
                )
            mockMvc
                .perform(put("/api/v1/commute-routes/$routeId/legs").json(mapOf("legs" to legs)))
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("alternate")))

            mockMvc
                .perform(
                    put("/api/v1/commute-routes/$routeId/legs").json(
                        mapOf("legs" to listOf(walk(2, baseLat, baseLng, baseLat + 0.001, baseLng))),
                    ),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("contiguous")))

            mockMvc
                .perform(
                    put("/api/v1/commute-routes/$routeId/legs").json(
                        mapOf("legs" to listOf(mapOf("seqOrder" to 1, "legType" to "WALK"))),
                    ),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("startLat")))

            mockMvc
                .perform(put("/api/v1/commute-routes/99999/legs").json(mapOf("legs" to legs)))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `nearby stops are sorted by distance and limited to radius`() {
            val near = createStop("BUS", "near", baseLat + 0.001, baseLng) // ~111m
            val mid = createStop("SUBWAY", "mid", baseLat, baseLng + 0.003) // ~265m
            createStop("BUS", "far", baseLat + 0.01, baseLng) // ~1.1km

            val body = getJson("/api/v1/transit-stops/nearby?lat=$baseLat&lng=$baseLng&radiusM=500")
            assertEquals(listOf(near, mid), body.map { it["id"].asLong() })
            val distances = body.map { it["distanceM"].asDouble() }
            assertEquals(distances.sorted(), distances)
            assertTrue(distances.all { it <= 500 })

            val busOnly = getJson("/api/v1/transit-stops/nearby?lat=$baseLat&lng=$baseLng&mode=BUS")
            assertEquals(listOf(near), busOnly.map { it["id"].asLong() })

            val wide = getJson("/api/v1/transit-stops/nearby?lat=$baseLat&lng=$baseLng&radiusM=5000")
            assertEquals(3, wide.size())

            mockMvc
                .perform(get("/api/v1/transit-stops/nearby?lat=$baseLat&lng=$baseLng&radiusM=5001").authed())
                .andExpect(status().isBadRequest)
        }

        @Test
        fun `nearby traffic signals and line search`() {
            val signal = createSignal(baseLat + 0.001, baseLng)
            createSignal(baseLat + 0.05, baseLng)
            val body = getJson("/api/v1/traffic-signals/nearby?lat=$baseLat&lng=$baseLng")
            assertEquals(listOf(signal), body.map { it["id"].asLong() })

            val bus = createLine("BUS", "5511", "B-5511")
            createLine("SUBWAY", "2호선", "S-2")
            val byName = getJson("/api/v1/transit-lines/search?keyword=551")
            assertEquals(listOf(bus), byName.map { it["id"].asLong() })
            val byExternal = getJson("/api/v1/transit-lines/search?mode=BUS&keyword=b-55")
            assertEquals(listOf(bus), byExternal.map { it["id"].asLong() })
            val none = getJson("/api/v1/transit-lines/search?mode=SUBWAY&keyword=5511")
            assertEquals(0, none.size())
        }

        @Test
        fun `signal crossings can only be set on WALK legs`() {
            val lineId = createLine("BUS", "1234", "L-2")
            val boardId = createStop("BUS", "board", baseLat + 0.002, baseLng)
            val alightId = createStop("BUS", "alight", baseLat + 0.02, baseLng)
            val signalId = createSignal(baseLat + 0.001, baseLng)
            val routeId = createRoute()
            val legs =
                listOf(
                    walk(1, baseLat, baseLng, baseLat + 0.002, baseLng),
                    transit(2, lineId, boardId, alightId),
                    walk(3, baseLat + 0.02, baseLng, baseLat + 0.021, baseLng),
                )
            mockMvc
                .perform(put("/api/v1/commute-routes/$routeId/legs").json(mapOf("legs" to legs)))
                .andExpect(status().isOk)
            val detail = getJson("/api/v1/commute-routes/$routeId")
            val walkLegId = detail["legs"][0]["id"].asLong()
            val transitLegId = detail["legs"][1]["id"].asLong()
            val crossings =
                mapOf(
                    "crossings" to
                        listOf(
                            mapOf("trafficSignalId" to signalId, "seqOrder" to 1, "approachDir" to "nt"),
                        ),
                )

            mockMvc
                .perform(put("/api/v1/route-legs/$transitLegId/signal-crossings").json(crossings))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("WALK")))

            mockMvc
                .perform(put("/api/v1/route-legs/$walkLegId/signal-crossings").json(crossings))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.signalCrossings.length()").value(1))
                .andExpect(jsonPath("$.signalCrossings[0].trafficSignalId").value(signalId))
                .andExpect(jsonPath("$.signalCrossings[0].signalKind").value("Pd"))

            val badDir =
                mapOf(
                    "crossings" to
                        listOf(
                            mapOf("trafficSignalId" to signalId, "seqOrder" to 1, "approachDir" to "up"),
                        ),
                )
            mockMvc
                .perform(put("/api/v1/route-legs/$walkLegId/signal-crossings").json(badDir))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value(containsString("approachDir")))

            val after = getJson("/api/v1/commute-routes/$routeId")
            assertEquals(1, after["legs"][0]["signalCrossings"].size())
        }

        @Test
        fun `replacing legs keeps leg ids, crossings and measured history`() {
            val lineId = createLine("BUS", "1234", "L-3")
            val boardId = createStop("BUS", "board", baseLat + 0.002, baseLng)
            val alightId = createStop("BUS", "alight", baseLat + 0.02, baseLng)
            val signalId = createSignal(baseLat + 0.001, baseLng)
            val routeId = createRoute()
            val legs =
                listOf(
                    walk(1, baseLat, baseLng, baseLat + 0.002, baseLng),
                    transit(2, lineId, boardId, alightId),
                    walk(3, baseLat + 0.02, baseLng, baseLat + 0.021, baseLng),
                )
            val ids = putLegs(routeId, legs)["legs"].map { it["id"].asLong() }
            val crossings =
                mapOf(
                    "crossings" to listOf(mapOf("trafficSignalId" to signalId, "seqOrder" to 1, "approachDir" to "nt")),
                )
            mockMvc
                .perform(put("/api/v1/route-legs/${ids[0]}/signal-crossings").json(crossings))
                .andExpect(status().isOk)
            val tripId =
                postJson(
                    "/api/v1/commute-trips",
                    mapOf("routeId" to routeId, "tripDate" to "2026-09-21"),
                )["id"]
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf("routeLegId" to ids[1], "arrivedAtStopAt" to "2026-09-20T22:36:00Z"),
            )

            // id 없이 같은 구조를 다시 보내면 seqOrder+legType으로 기존 구간을 제자리에서 고친다.
            val tweaked = listOf(legs[0], legs[1] + ("plannedTravelSec" to 2500), legs[2])
            val after = putLegs(routeId, tweaked.reversed())
            assertEquals(ids, after["legs"].map { it["id"].asLong() })
            assertEquals(2500, after["legs"][1]["plannedTravelSec"].asInt())
            assertEquals(1, after["legs"][0]["signalCrossings"].size())
            assertEquals(1, boardingAttemptCount())

            // id로 재정렬: 양끝 WALK를 서로 바꿔도 UNIQUE 충돌 없이 id와 crossing이 따라간다.
            val swapped =
                listOf(
                    legs[0] + ("id" to ids[2]),
                    legs[1] + ("id" to ids[1]),
                    legs[2] + ("id" to ids[0]),
                )
            val reordered = putLegs(routeId, swapped)
            assertEquals(listOf(ids[2], ids[1], ids[0]), reordered["legs"].map { it["id"].asLong() })
            assertEquals(listOf(1, 2, 3), reordered["legs"].map { it["seqOrder"].asInt() })
            assertEquals(1, reordered["legs"][2]["signalCrossings"].size())
            assertEquals(1, boardingAttemptCount())

            // 실측 기록이 붙은 TRANSIT 구간을 빼는 교체는 409, 다른 경로의 구간 id는 400. 어느 쪽도 데이터를 건드리지 않는다.
            mockMvc
                .perform(
                    put(
                        "/api/v1/commute-routes/$routeId/legs",
                    ).json(mapOf("legs" to listOf(legs[0] + ("id" to ids[0])))),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.detail").value(containsString(ids[1].toString())))
            val otherRouteId = createRoute()
            mockMvc
                .perform(
                    put("/api/v1/commute-routes/$otherRouteId/legs").json(
                        mapOf("legs" to listOf(legs[0] + ("id" to ids[0]))),
                    ),
                ).andExpect(status().isBadRequest)
            assertEquals(1, boardingAttemptCount())
            assertEquals(
                3,
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM route_legs WHERE commute_route_id = ?",
                    Int::class.java,
                    routeId,
                ),
            )
        }

        @Test
        fun `walk coordinates outside WGS84 are rejected with 400`() {
            val routeId = createRoute()
            mockMvc
                .perform(
                    put("/api/v1/commute-routes/$routeId/legs").json(
                        mapOf("legs" to listOf(walk(1, 999.0, baseLng, baseLat, baseLng))),
                    ),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errors[0]").value(containsString("startLat")))
        }

        private fun putLegs(
            routeId: Long,
            legs: List<Map<String, Any>>,
        ): JsonNode {
            val response =
                mockMvc
                    .perform(put("/api/v1/commute-routes/$routeId/legs").json(mapOf("legs" to legs)))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            return objectMapper.readTree(response)
        }

        private fun boardingAttemptCount(): Int =
            jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java)!!

        private fun MockHttpServletRequestBuilder.authed(): MockHttpServletRequestBuilder = header("X-Api-Token", token)

        private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
            authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))

        private fun postJson(
            path: String,
            body: Any,
        ): JsonNode {
            val response =
                mockMvc
                    .perform(post(path).json(body))
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            return objectMapper.readTree(response)
        }

        private fun getJson(path: String): JsonNode {
            val response =
                mockMvc
                    .perform(get(path).authed())
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            return objectMapper.readTree(response)
        }

        private fun createRoute(): Long =
            postJson(
                "/api/v1/commute-routes",
                mapOf(
                    "name" to "집→회사",
                    "direction" to "TO_WORK",
                    "originLat" to baseLat,
                    "originLng" to baseLng,
                    "destinationLat" to baseLat + 0.021,
                    "destinationLng" to baseLng,
                ),
            )["id"].asLong()

        private fun createLine(
            mode: String,
            name: String,
            externalId: String,
        ): Long =
            postJson(
                "/api/v1/transit-lines",
                mapOf("mode" to mode, "name" to name, "externalId" to externalId, "stdgCd" to "1100000000"),
            )["id"].asLong()

        private fun createStop(
            mode: String,
            name: String,
            lat: Double,
            lng: Double,
        ): Long =
            postJson(
                "/api/v1/transit-stops",
                mapOf("mode" to mode, "name" to name, "lat" to lat, "lng" to lng),
            )["id"].asLong()

        private fun createSignal(
            lat: Double,
            lng: Double,
        ): Long = postJson("/api/v1/traffic-signals", mapOf("lat" to lat, "lng" to lng))["id"].asLong()

        private fun walk(
            seq: Int,
            startLat: Double,
            startLng: Double,
            endLat: Double,
            endLng: Double,
        ): Map<String, Any> =
            mapOf(
                "seqOrder" to seq,
                "legType" to "WALK",
                "startLat" to startLat,
                "startLng" to startLng,
                "endLat" to endLat,
                "endLng" to endLng,
            )

        private fun transit(
            seq: Int,
            lineId: Long,
            boardId: Long,
            alightId: Long,
        ): Map<String, Any> =
            mapOf(
                "seqOrder" to seq,
                "legType" to "TRANSIT",
                "transitLineId" to lineId,
                "boardStopId" to boardId,
                "alightStopId" to alightId,
                "plannedTravelSec" to 900,
            )
    }
