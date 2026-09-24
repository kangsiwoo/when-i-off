package com.kangsiwoo.whenioff.trip.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.route.domain.CommuteDirection
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.trip.domain.DepartureRecommendation
import com.kangsiwoo.whenioff.trip.domain.DepartureRecommendationRepository
import com.kangsiwoo.whenioff.user.domain.User
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
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationApiIT {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var commuteRouteRepository: CommuteRouteRepository

    @Autowired lateinit var recommendationRepository: DepartureRecommendationRepository

    private val targetArrivalAt: Instant = Instant.parse("2026-09-21T00:00:00Z")
    private val targetDate: LocalDate = LocalDate.parse("2026-09-21")

    private lateinit var route: CommuteRoute

    @BeforeEach
    fun seedRoute() {
        jdbcTemplate.execute(
            "TRUNCATE departure_recommendations, route_legs, commute_routes RESTART IDENTITY CASCADE",
        )
        jdbcTemplate.update("DELETE FROM users WHERE id <> ?", DefaultUser.ID)
        route = saveRoute(userRepository.getReferenceById(DefaultUser.ID), "출근")
    }

    @Test
    fun `returns the recommendation stored for the target arrival time`() {
        saveRecommendation(
            route = route,
            leaveHomeAt = Instant.parse("2026-09-20T22:30:00Z"),
            catchProbability = 0.87,
            bufferSeconds = 300,
            computedAt = Instant.parse("2026-09-20T18:00:00Z"),
        )

        val body = getRecommendation("?targetArrivalAt=2026-09-21T00:00:00Z").andExpect { status { isOk() } }.json()
        assertEquals("2026-09-20T22:30:00Z", body["recommendedLeaveHomeAt"].asText())
        assertEquals("2026-09-21T00:00:00Z", body["targetArrivalAt"].asText())
        assertEquals(0.87, body["catchProbability"].asDouble())
        assertEquals(300, body["bufferSeconds"].asInt())
        assertEquals("v1", body["modelVersion"].asText())
        assertEquals("2026-09-20T18:00:00Z", body["computedAt"].asText())

        // 목표 시각과 무관한 latest도 같은 한 건을 본다.
        val latest = getRecommendation("/latest").andExpect { status { isOk() } }.json()
        assertEquals("2026-09-20T22:30:00Z", latest["recommendedLeaveHomeAt"].asText())
    }

    @Test
    fun `no stored recommendation gives 404 rather than an empty 200`() {
        getRecommendation("?targetArrivalAt=2026-09-21T00:00:00Z")
            .andExpect { status { isNotFound() } }
            .andExpect { content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) } }
        getRecommendation("/latest").andExpect { status { isNotFound() } }

        // 다른 목표 시각의 추천은 있어도, 물어본 시각의 추천이 없으면 404다.
        saveRecommendation(route, Instant.parse("2026-09-20T22:30:00Z"), 0.87, 300)
        getRecommendation("?targetArrivalAt=2026-09-21T01:00:00Z").andExpect { status { isNotFound() } }
    }

    @Test
    fun `another user's route gives 404`() {
        val otherUser = userRepository.save(User(email = "other@when-i-off.local", displayName = "other"))
        val otherRoute = saveRoute(otherUser, "남의 출근")
        saveRecommendation(otherRoute, Instant.parse("2026-09-20T22:30:00Z"), 0.87, 300)

        mockMvc
            .get("/api/v1/commute-routes/${otherRoute.id}/recommendation?targetArrivalAt=2026-09-21T00:00:00Z") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
            }.andExpect { status { isNotFound() } }
        mockMvc
            .get("/api/v1/commute-routes/${otherRoute.id}/recommendation/latest") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
            }.andExpect { status { isNotFound() } }

        // 없는 경로도 같은 404다 (존재 여부를 숨긴다).
        mockMvc
            .get("/api/v1/commute-routes/999999/recommendation/latest") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `history of several recommendations returns the most recent one`() {
        // 같은 (경로, 목표 시각)의 과거 추천은 지우지 않고 쌓인다 (DATA_MODEL). 조회는 최신 한 건만 본다.
        saveRecommendation(
            route = route,
            leaveHomeAt = Instant.parse("2026-09-20T22:30:00Z"),
            catchProbability = 0.70,
            bufferSeconds = 300,
            computedAt = Instant.parse("2026-09-20T18:00:00Z"),
        )
        saveRecommendation(
            route = route,
            leaveHomeAt = Instant.parse("2026-09-20T22:24:00Z"),
            catchProbability = 0.91,
            bufferSeconds = 660,
            computedAt = Instant.parse("2026-09-20T21:00:00Z"),
        )

        val body = getRecommendation("?targetArrivalAt=2026-09-21T00:00:00Z").andExpect { status { isOk() } }.json()
        assertEquals("2026-09-20T22:24:00Z", body["recommendedLeaveHomeAt"].asText())
        assertEquals(0.91, body["catchProbability"].asDouble())
        assertEquals(660, body["bufferSeconds"].asInt())
        assertEquals("2026-09-20T21:00:00Z", body["computedAt"].asText())

        // latest는 목표 시각이 달라도 계산 시각이 가장 늦은 것을 고른다.
        saveRecommendation(
            route = route,
            leaveHomeAt = Instant.parse("2026-09-20T23:10:00Z"),
            catchProbability = 0.55,
            bufferSeconds = 120,
            computedAt = Instant.parse("2026-09-20T22:00:00Z"),
            targetArrivalAt = Instant.parse("2026-09-21T01:00:00Z"),
        )
        val latest = getRecommendation("/latest").andExpect { status { isOk() } }.json()
        assertEquals("2026-09-20T23:10:00Z", latest["recommendedLeaveHomeAt"].asText())
        assertEquals("2026-09-21T01:00:00Z", latest["targetArrivalAt"].asText())

        // 시각을 지정한 조회는 앞의 목표 시각 것을 그대로 본다.
        val pinned = getRecommendation("?targetArrivalAt=2026-09-21T00:00:00Z").andExpect { status { isOk() } }.json()
        assertEquals("2026-09-20T22:24:00Z", pinned["recommendedLeaveHomeAt"].asText())
    }

    @Test
    fun `recommendations computed in the same transaction are broken by id, newest last`() {
        // computed_at 기본값이 트랜잭션 시각이라 한 번에 들어간 행끼리는 값이 같을 수 있다.
        // 그때도 어느 행이 나올지 비결정적이면 안 되므로 나중에 들어간 행(= 큰 id)을 고른다.
        val sameInstant = Instant.parse("2026-09-20T21:00:00Z")
        saveRecommendation(
            route = route,
            leaveHomeAt = Instant.parse("2026-09-20T22:30:00Z"),
            catchProbability = 0.70,
            bufferSeconds = 300,
            computedAt = sameInstant,
        )
        val newer =
            saveRecommendation(
                route = route,
                leaveHomeAt = Instant.parse("2026-09-20T22:24:00Z"),
                catchProbability = 0.91,
                bufferSeconds = 660,
                computedAt = sameInstant,
            )

        val body = getRecommendation("?targetArrivalAt=2026-09-21T00:00:00Z").andExpect { status { isOk() } }.json()
        assertEquals(newer.recommendedLeaveHomeAt.toString(), body["recommendedLeaveHomeAt"].asText())
        assertEquals(0.91, body["catchProbability"].asDouble())

        val latest = getRecommendation("/latest").andExpect { status { isOk() } }.json()
        assertEquals(newer.recommendedLeaveHomeAt.toString(), latest["recommendedLeaveHomeAt"].asText())
    }

    @Test
    fun `missing or unparsable targetArrivalAt gives 400`() {
        getRecommendation("").andExpect { status { isBadRequest() } }
        getRecommendation("?targetArrivalAt=").andExpect { status { isBadRequest() } }
        getRecommendation("?targetArrivalAt=2026-09-21").andExpect { status { isBadRequest() } }
        getRecommendation("?targetArrivalAt=not-a-time").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `requests without api token are rejected`() {
        mockMvc
            .get("/api/v1/commute-routes/${route.id}/recommendation?targetArrivalAt=2026-09-21T00:00:00Z")
            .andExpect { status { isUnauthorized() } }
        mockMvc
            .get("/api/v1/commute-routes/${route.id}/recommendation/latest")
            .andExpect { status { isUnauthorized() } }
    }

    private fun getRecommendation(suffix: String): ResultActionsDsl =
        mockMvc.get("/api/v1/commute-routes/${route.id}/recommendation$suffix") {
            header(ApiTokenFilter.HEADER, properties.apiToken)
        }

    private fun ResultActionsDsl.json(): JsonNode =
        objectMapper.readTree(andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun saveRoute(
        owner: User,
        name: String,
    ): CommuteRoute =
        commuteRouteRepository.save(
            CommuteRoute(
                user = owner,
                name = name,
                direction = CommuteDirection.TO_WORK,
                originLat = 37.39,
                originLng = 127.11,
                destinationLat = 37.50,
                destinationLng = 127.03,
            ),
        )

    private fun saveRecommendation(
        route: CommuteRoute,
        leaveHomeAt: Instant,
        catchProbability: Double,
        bufferSeconds: Int,
        computedAt: Instant = Instant.parse("2026-09-20T18:00:00Z"),
        targetArrivalAt: Instant = this.targetArrivalAt,
    ): DepartureRecommendation =
        recommendationRepository.save(
            DepartureRecommendation(
                user = route.user,
                commuteRoute = route,
                targetDate = targetDate,
                targetArrivalAt = targetArrivalAt,
                recommendedLeaveHomeAt = leaveHomeAt,
                catchProbability = catchProbability,
                bufferSeconds = bufferSeconds,
                modelVersion = "v1",
                computedAt = computedAt,
            ),
        )
}
