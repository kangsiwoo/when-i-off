package com.kangsiwoo.whenioff.trip.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.route.domain.CommuteDirection
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripApiTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var properties: WioProperties

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var commuteRouteRepository: CommuteRouteRepository

    @Autowired lateinit var routeLegRepository: RouteLegRepository

    @Autowired lateinit var transitLineRepository: TransitLineRepository

    @Autowired lateinit var transitStopRepository: TransitStopRepository

    private lateinit var route: CommuteRoute
    private lateinit var walkLeg: RouteLeg
    private lateinit var transitLeg: RouteLeg

    @BeforeEach
    fun seedRoute() {
        jdbcTemplate.execute(
            "TRUNCATE gps_traces, boarding_attempts, commute_trips, route_legs, commute_routes, " +
                "transit_stops, transit_lines RESTART IDENTITY CASCADE",
        )
        val user = userRepository.getReferenceById(DefaultUser.ID)
        route =
            commuteRouteRepository.save(
                CommuteRoute(
                    user = user,
                    name = "출근",
                    direction = CommuteDirection.TO_WORK,
                    originLat = 37.39,
                    originLng = 127.11,
                    destinationLat = 37.50,
                    destinationLng = 127.03,
                ),
            )
        walkLeg = saveWalkLeg(route, seqOrder = 1)
        transitLeg = saveTransitLeg(route, seqOrder = 2)
        saveWalkLeg(route, seqOrder = 3)
    }

    @Test
    fun `golden scenario - trip, attempt upsert, result patch, history`() {
        val created =
            postJson(
                "/api/v1/commute-trips",
                mapOf("routeId" to route.id, "tripDate" to "2026-09-21", "leftHomeAt" to "2026-09-20T22:30:00Z"),
            ).andExpect { status { isCreated() } }.json()
        val tripId = created["id"].asLong()
        assertEquals(route.id, created["routeId"].asLong())
        assertEquals("2026-09-20T22:30:00Z", created["leftHomeAt"].asText())

        val first =
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf(
                    "routeLegId" to transitLeg.id,
                    "arrivedAtStopAt" to "2026-09-20T22:36:00Z",
                    "vehicleScheduledOrPredictedAt" to "2026-09-20T22:40:00Z",
                ),
            ).andExpect { status { isCreated() } }.json()
        val attemptId = first["id"].asLong()
        assertEquals("UNKNOWN", first["result"].asText())
        assertEquals(1, first["attemptSeq"].asInt()) // 생략하면 첫 시도 (#38)

        val upserted =
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf("routeLegId" to transitLeg.id, "vehicleActualDepartureAt" to "2026-09-20T22:41:30Z"),
            ).andExpect { status { isOk() } }.json()
        assertEquals(attemptId, upserted["id"].asLong())
        assertEquals("2026-09-20T22:36:00Z", upserted["arrivedAtStopAt"].asText())
        assertEquals("2026-09-20T22:41:30Z", upserted["vehicleActualDepartureAt"].asText())
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java))

        val patched =
            patchJson(
                "/api/v1/boarding-attempts/$attemptId",
                mapOf("result" to "CAUGHT", "alightedAt" to "2026-09-20T23:20:00Z"),
            ).andExpect { status { isOk() } }.json()
        assertEquals("CAUGHT", patched["result"].asText())

        patchJson("/api/v1/commute-trips/$tripId", mapOf("arrivedDestinationAt" to "2026-09-20T23:28:00Z"))
            .andExpect { status { isOk() } }

        val history =
            mockMvc
                .get("/api/v1/commute-trips") {
                    header(ApiTokenFilter.HEADER, properties.apiToken)
                    param("routeId", route.id.toString())
                    param("from", "2026-09-01")
                    param("to", "2026-09-30")
                }.andExpect { status { isOk() } }
                .json()
        assertEquals(1, history.size())
        val trip = history[0]
        assertEquals(tripId, trip["id"].asLong())
        assertEquals("2026-09-20T23:28:00Z", trip["arrivedDestinationAt"].asText())
        assertEquals(1, trip["boardingAttempts"].size())
        val embedded = trip["boardingAttempts"][0]
        assertEquals(attemptId, embedded["id"].asLong())
        assertEquals(transitLeg.id, embedded["routeLegId"].asLong())
        assertEquals("CAUGHT", embedded["result"].asText())
        assertEquals("2026-09-20T23:20:00Z", embedded["alightedAt"].asText())
    }

    @Test
    fun `history is ordered by trip date desc and filtered by range`() {
        for (date in listOf("2026-09-01", "2026-09-03", "2026-09-02")) {
            postJson("/api/v1/commute-trips", mapOf("routeId" to route.id, "tripDate" to date))
                .andExpect { status { isCreated() } }
        }
        val all = mockMvc.get("/api/v1/commute-trips") { header(ApiTokenFilter.HEADER, properties.apiToken) }.json()
        assertEquals(listOf("2026-09-03", "2026-09-02", "2026-09-01"), all.map { it["tripDate"].asText() })

        val ranged =
            mockMvc
                .get("/api/v1/commute-trips") {
                    header(ApiTokenFilter.HEADER, properties.apiToken)
                    param("from", "2026-09-02")
                }.json()
        assertEquals(listOf("2026-09-03", "2026-09-02"), ranged.map { it["tripDate"].asText() })
    }

    @Test
    fun `boarding attempt rejects WALK leg and leg of another route`() {
        val tripId = createTrip()
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to walkLeg.id))
            .andExpect { status { isBadRequest() } }

        val otherRoute =
            commuteRouteRepository.save(
                CommuteRoute(
                    user = userRepository.getReferenceById(DefaultUser.ID),
                    name = "퇴근",
                    direction = CommuteDirection.TO_HOME,
                    originLat = 0.0,
                    originLng = 0.0,
                    destinationLat = 0.0,
                    destinationLng = 0.0,
                ),
            )
        val foreignLeg = saveTransitLeg(otherRoute, seqOrder = 1)
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to foreignLeg.id))
            .andExpect { status { isBadRequest() } }
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to 999_999))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `boarding attempt creation rejects alightedAt before departure`() {
        val tripId = createTrip()
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf(
                "routeLegId" to transitLeg.id,
                "vehicleActualDepartureAt" to "2026-09-20T22:41:30Z",
                "alightedAt" to "2026-09-20T22:30:00Z",
            ),
        ).andExpect { status { isBadRequest() } }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java))
    }

    @Test
    fun `concurrent first upserts for the same leg produce one row and no 409`() {
        val tripId = createTrip()
        val body =
            objectMapper.writeValueAsString(
                mapOf("routeLegId" to transitLeg.id, "arrivedAtStopAt" to "2026-09-20T22:36:00Z"),
            )
        val workers = 4
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        val statuses =
            try {
                val futures =
                    (1..workers).map {
                        pool.submit(
                            Callable {
                                start.await()
                                mockMvc
                                    .post("/api/v1/commute-trips/$tripId/boarding-attempts") {
                                        header(ApiTokenFilter.HEADER, properties.apiToken)
                                        contentType = MediaType.APPLICATION_JSON
                                        content = body
                                    }.andReturn()
                                    .response.status
                            },
                        )
                    }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(1, statuses.count { it == 201 }, "$statuses")
        assertEquals(workers - 1, statuses.count { it == 200 }, "$statuses")
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java))
    }

    // ── #37: 재전송 흡수 ─────────────────────────────────────────────

    @Test
    fun `resending the same create returns the existing trip instead of a duplicate`() {
        // 지하에서 "집 나섬" 응답을 못 받은 앱이 같은 요청을 다시 보내는 상황.
        val body = mapOf("routeId" to route.id, "tripDate" to "2026-09-21", "leftHomeAt" to "2026-09-20T22:30:00.123Z")
        val first = postJson("/api/v1/commute-trips", body).andExpect { status { isCreated() } }.json()
        val again = postJson("/api/v1/commute-trips", body).andExpect { status { isOk() } }.json()

        assertEquals(first["id"].asLong(), again["id"].asLong())
        assertEquals(1, tripCount())
    }

    @Test
    fun `resent create returns the trip with the attempts recorded since`() {
        // 재전송 사이에 다른 기록이 붙었으면 그것까지 보여줘야 앱이 상태를 맞출 수 있다.
        val body = mapOf("routeId" to route.id, "tripDate" to "2026-09-21", "leftHomeAt" to "2026-09-20T22:30:00Z")
        val tripId = postJson("/api/v1/commute-trips", body).json()["id"].asLong()
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf("routeLegId" to transitLeg.id, "arrivedAtStopAt" to "2026-09-20T22:36:00Z"),
        ).andExpect { status { isCreated() } }

        val again = postJson("/api/v1/commute-trips", body).andExpect { status { isOk() } }.json()
        assertEquals(1, again["boardingAttempts"].size())
    }

    @Test
    fun `trips without leftHomeAt are never merged`() {
        // 키가 없으면 재전송인지 알 수 없다. 합치면 서로 다른 출근이 하나로 뭉개진다.
        val body = mapOf("routeId" to route.id, "tripDate" to "2026-09-21")
        postJson("/api/v1/commute-trips", body).andExpect { status { isCreated() } }
        postJson("/api/v1/commute-trips", body).andExpect { status { isCreated() } }
        assertEquals(2, tripCount())
    }

    @Test
    fun `same leftHomeAt with a different tripDate is a conflict, not a silent merge`() {
        // 재전송이면 본문이 같다. 날짜만 다르면 클라이언트가 날짜를 다르게 계산한 버그다(UTC로 뽑는 등).
        postJson(
            "/api/v1/commute-trips",
            mapOf("routeId" to route.id, "tripDate" to "2026-09-21", "leftHomeAt" to "2026-09-20T22:30:00Z"),
        ).andExpect { status { isCreated() } }
        postJson(
            "/api/v1/commute-trips",
            mapOf("routeId" to route.id, "tripDate" to "2026-09-20", "leftHomeAt" to "2026-09-20T22:30:00Z"),
        ).andExpect { status { isConflict() } }
        assertEquals(1, tripCount())
    }

    @Test
    fun `concurrent resends of the same create produce one trip and no error`() {
        // 서비스의 "먼저 조회"만으로는 둘 다 "없음"을 보고 삽입하는 경쟁을 못 막는다. 유일 인덱스(V4)가 막고,
        // 진 쪽은 컨트롤러가 한 번 다시 태워 기존 trip을 돌려준다.
        val body =
            objectMapper.writeValueAsString(
                mapOf("routeId" to route.id, "tripDate" to "2026-09-21", "leftHomeAt" to "2026-09-20T22:30:00Z"),
            )
        val workers = 4
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        val statuses =
            try {
                val futures =
                    (1..workers).map {
                        pool.submit(
                            Callable {
                                start.await()
                                mockMvc
                                    .post("/api/v1/commute-trips") {
                                        header(ApiTokenFilter.HEADER, properties.apiToken)
                                        contentType = MediaType.APPLICATION_JSON
                                        content = body
                                    }.andReturn()
                                    .response.status
                            },
                        )
                    }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(1, statuses.count { it == 201 }, "$statuses")
        assertEquals(workers - 1, statuses.count { it == 200 }, "$statuses")
        assertEquals(1, tripCount())
    }

    // ── #37: trip과 탑승 시도 사이의 시각 순서 ─────────────────────────

    @Test
    fun `boarding attempt observed before leaving home is rejected`() {
        // 집→역 도보 시간이 음수가 되어 도보 속도 보정이 오염된다.
        val tripId = createTripLeftAt("2026-09-20T22:30:00Z")
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf("routeLegId" to transitLeg.id, "arrivedAtStopAt" to "2026-09-20T22:20:00Z"),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("arrivedAtStopAt must not be before the trip's leftHomeAt") }
        }
        assertEquals(0, attemptCount())
    }

    @Test
    fun `boarding attempt after arriving at the destination is rejected`() {
        val tripId = createTripLeftAt("2026-09-20T22:30:00Z")
        patchJson("/api/v1/commute-trips/$tripId", mapOf("arrivedDestinationAt" to "2026-09-20T23:28:00Z"))
            .andExpect { status { isOk() } }
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf("routeLegId" to transitLeg.id, "alightedAt" to "2026-09-20T23:40:00Z"),
        ).andExpect { status { isBadRequest() } }
        assertEquals(0, attemptCount())
    }

    @Test
    fun `trip times cannot be moved past attempts already recorded`() {
        // 탑승 시도 쪽에서만 검사하면, 시도를 먼저 기록하고 trip 시각을 나중에 고쳐 같은 모순을 만들 수 있다.
        val tripId = createTripLeftAt("2026-09-20T22:30:00Z")
        val attemptId =
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf("routeLegId" to transitLeg.id, "arrivedAtStopAt" to "2026-09-20T22:36:00Z"),
            ).json()["id"].asLong()
        patchJson("/api/v1/boarding-attempts/$attemptId", mapOf("alightedAt" to "2026-09-20T23:20:00Z"))
            .andExpect { status { isOk() } }

        patchJson("/api/v1/commute-trips/$tripId", mapOf("leftHomeAt" to "2026-09-20T22:40:00Z"))
            .andExpect { status { isBadRequest() } }
        patchJson("/api/v1/commute-trips/$tripId", mapOf("arrivedDestinationAt" to "2026-09-20T23:10:00Z"))
            .andExpect { status { isBadRequest() } }

        // 거부된 PATCH는 아무것도 바꾸지 않는다 (트랜잭션 롤백).
        val trip = getTrips()[0]
        assertEquals("2026-09-20T22:30:00Z", trip["leftHomeAt"].asText())
        assertTrue(trip["arrivedDestinationAt"] == null || trip["arrivedDestinationAt"].isNull)
    }

    @Test
    fun `a predicted time before leaving home is still accepted`() {
        // vehicleScheduledOrPredictedAt은 관측이 아니라 그 순간 시스템이 알려준 예측의 스냅샷이다.
        // 오래된 예측이 집 나섬보다 이를 수 있으므로 시각 창 검사에서 뺐다.
        val tripId = createTripLeftAt("2026-09-20T22:30:00Z")
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf(
                "routeLegId" to transitLeg.id,
                "arrivedAtStopAt" to "2026-09-20T22:36:00Z",
                "vehicleScheduledOrPredictedAt" to "2026-09-20T22:25:00Z",
            ),
        ).andExpect { status { isCreated() } }
    }

    // ── #38: 구간당 탑승 시도 여러 건 (차 한 대 = 한 행) ─────────────────

    @Test
    fun `missed then caught keeps both attempts and each resend is idempotent`() {
        // 22:43 차를 놓치고 22:58 차를 탄다. 예전에는 두 번째가 첫 번째를 덮어써 가짜 예측 오차가 생겼다.
        val tripId = createTripLeftAt("2026-09-20T22:30:00Z")
        val missed =
            mapOf(
                "routeLegId" to transitLeg.id,
                "attemptSeq" to 1,
                "arrivedAtStopAt" to "2026-09-20T22:43:30Z",
                "vehicleScheduledOrPredictedAt" to "2026-09-20T22:43:00Z",
                "vehicleActualDepartureAt" to "2026-09-20T22:43:00Z",
                "result" to "MISSED",
            )
        val caught =
            mapOf(
                "routeLegId" to transitLeg.id,
                "attemptSeq" to 2,
                "vehicleScheduledOrPredictedAt" to "2026-09-20T22:57:00Z",
                "vehicleActualDepartureAt" to "2026-09-20T22:58:00Z",
                "result" to "CAUGHT",
            )
        val first =
            postJson("/api/v1/commute-trips/$tripId/boarding-attempts", missed)
                .andExpect { status { isCreated() } }
                .json()
        val second =
            postJson("/api/v1/commute-trips/$tripId/boarding-attempts", caught)
                .andExpect { status { isCreated() } }
                .json()
        assertEquals(2, attemptCount())

        // 재전송은 seq마다 제자리 갱신이다 (행이 늘지 않는다).
        val missedAgain =
            postJson("/api/v1/commute-trips/$tripId/boarding-attempts", missed)
                .andExpect { status { isOk() } }
                .json()
        val caughtAgain =
            postJson("/api/v1/commute-trips/$tripId/boarding-attempts", caught)
                .andExpect { status { isOk() } }
                .json()
        assertEquals(first["id"].asLong(), missedAgain["id"].asLong())
        assertEquals(second["id"].asLong(), caughtAgain["id"].asLong())
        assertEquals(2, attemptCount())

        val attempts = getTrips()[0]["boardingAttempts"]
        assertEquals(listOf(1, 2), attempts.map { it["attemptSeq"].asInt() })
        assertEquals(listOf("MISSED", "CAUGHT"), attempts.map { it["result"].asText() })
        assertEquals(
            listOf("2026-09-20T22:43:00Z", "2026-09-20T22:57:00Z"),
            attempts.map { it["vehicleScheduledOrPredictedAt"].asText() },
        )
        assertEquals(
            listOf("2026-09-20T22:43:00Z", "2026-09-20T22:58:00Z"),
            attempts.map { it["vehicleActualDepartureAt"].asText() },
        )
        assertEquals("2026-09-20T22:43:30Z", attempts[0]["arrivedAtStopAt"].asText())
        assertTrue(attempts[1]["arrivedAtStopAt"] == null || attempts[1]["arrivedAtStopAt"].isNull)
    }

    @Test
    fun `attemptSeq must not skip ahead and is bounded`() {
        val tripId = createTrip()
        for (seq in listOf(2, 0, 21)) {
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf("routeLegId" to transitLeg.id, "attemptSeq" to seq),
            ).andExpect { status { isBadRequest() } }
        }
        assertEquals(0, attemptCount())

        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to transitLeg.id))
            .andExpect { status { isCreated() } }
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf("routeLegId" to transitLeg.id, "attemptSeq" to 3),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("attemptSeq 3 skips ahead: next attemptSeq for this leg is 2") }
        }
        assertEquals(1, attemptCount())
    }

    @Test
    fun `trip attempts are ordered by leg then attemptSeq`() {
        val tripId = createTrip()
        val laterLeg = saveTransitLeg(route, seqOrder = 4)
        // 삽입 순서(id)와 다르게: 2구간#1 → 4구간#1 → 2구간#2
        for ((leg, seq) in listOf(transitLeg to 1, laterLeg to 1, transitLeg to 2)) {
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf("routeLegId" to leg.id, "attemptSeq" to seq),
            ).andExpect { status { isCreated() } }
        }
        val attempts = getTrips()[0]["boardingAttempts"]
        assertEquals(
            listOf(transitLeg.id to 1, transitLeg.id to 2, laterLeg.id to 1),
            attempts.map { it["routeLegId"].asLong() to it["attemptSeq"].asInt() },
        )
    }

    @Test
    fun `a later attempt cannot precede the departure of an earlier one`() {
        val tripId = createTripLeftAt("2026-09-20T22:30:00Z")
        val firstId =
            postJson(
                "/api/v1/commute-trips/$tripId/boarding-attempts",
                mapOf(
                    "routeLegId" to transitLeg.id,
                    "vehicleActualDepartureAt" to "2026-09-20T22:43:00Z",
                    "result" to "MISSED",
                ),
            ).json()["id"].asLong()
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf(
                "routeLegId" to transitLeg.id,
                "attemptSeq" to 2,
                "vehicleActualDepartureAt" to "2026-09-20T22:40:00Z",
            ),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") {
                value("vehicleActualDepartureAt of attempt 2 must not be before vehicleActualDepartureAt of attempt 1")
            }
        }
        assertEquals(1, attemptCount())

        // 뒤 시도에 처음 정류장에 도착한 시각(앞 차가 떠나기 전)을 그대로 실어도 받는다 (#42).
        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf("routeLegId" to transitLeg.id, "attemptSeq" to 2, "arrivedAtStopAt" to "2026-09-20T22:42:00Z"),
        ).andExpect { status { isCreated() } }

        postJson(
            "/api/v1/commute-trips/$tripId/boarding-attempts",
            mapOf(
                "routeLegId" to transitLeg.id,
                "attemptSeq" to 2,
                "vehicleActualDepartureAt" to "2026-09-20T22:58:00Z",
            ),
        ).andExpect { status { isOk() } }
        // 앞 시도를 나중에 고쳐 순서를 뒤집는 것도 막는다 (PATCH도 같은 검사를 탄다).
        patchJson("/api/v1/boarding-attempts/$firstId", mapOf("vehicleActualDepartureAt" to "2026-09-20T23:00:00Z"))
            .andExpect { status { isBadRequest() } }
        assertEquals("2026-09-20T22:43:00Z", getTrips()[0]["boardingAttempts"][0]["vehicleActualDepartureAt"].asText())
    }

    @Test
    fun `concurrent resends of the same new attemptSeq produce one row and no error`() {
        val tripId = createTrip()
        postJson("/api/v1/commute-trips/$tripId/boarding-attempts", mapOf("routeLegId" to transitLeg.id))
            .andExpect { status { isCreated() } }
        val body =
            objectMapper.writeValueAsString(
                mapOf("routeLegId" to transitLeg.id, "attemptSeq" to 2, "result" to "CAUGHT"),
            )
        val workers = 4
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        val statuses =
            try {
                val futures =
                    (1..workers).map {
                        pool.submit(
                            Callable {
                                start.await()
                                mockMvc
                                    .post("/api/v1/commute-trips/$tripId/boarding-attempts") {
                                        header(ApiTokenFilter.HEADER, properties.apiToken)
                                        contentType = MediaType.APPLICATION_JSON
                                        content = body
                                    }.andReturn()
                                    .response.status
                            },
                        )
                    }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(1, statuses.count { it == 201 }, "$statuses")
        assertEquals(workers - 1, statuses.count { it == 200 }, "$statuses")
        assertEquals(2, attemptCount())
    }

    @Test
    fun `unknown trip and route give 404`() {
        postJson("/api/v1/commute-trips", mapOf("routeId" to 999_999, "tripDate" to "2026-09-21"))
            .andExpect { status { isNotFound() } }
        patchJson("/api/v1/commute-trips/999999", mapOf("arrivedDestinationAt" to "2026-09-20T23:28:00Z"))
            .andExpect { status { isNotFound() } }
        postJson("/api/v1/gps-traces/batch", mapOf("tripId" to 999_999, "points" to listOf(point(0))))
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `gps batch ignores duplicates on (user, recordedAt)`() {
        val tripId = createTrip()
        val points = (0 until 5).map(::point)

        val firstBatch =
            postJson("/api/v1/gps-traces/batch", mapOf("tripId" to tripId, "points" to points))
                .andExpect { status { isOk() } }
                .json()
        assertEquals(5, firstBatch["accepted"].asInt())
        assertEquals(0, firstBatch["ignored"].asInt())

        val resent = points.take(3) + (5 until 7).map(::point) + point(6)
        val secondBatch =
            postJson("/api/v1/gps-traces/batch", mapOf("points" to resent))
                .andExpect { status { isOk() } }
                .json()
        assertEquals(2, secondBatch["accepted"].asInt())
        assertEquals(4, secondBatch["ignored"].asInt())

        assertEquals(7, jdbcTemplate.queryForObject("SELECT count(*) FROM gps_traces", Int::class.java))
        assertEquals(
            5,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM gps_traces WHERE commute_trip_id = ?",
                Int::class.java,
                tripId,
            ),
        )
        val stored =
            jdbcTemplate.queryForMap(
                "SELECT speed_mps, accuracy_m FROM gps_traces ORDER BY recorded_at LIMIT 1",
            )
        assertEquals(1.0, stored["speed_mps"])
        assertEquals(10.0, stored["accuracy_m"])
    }

    @Test
    fun `gps batch rejects more than 500 points and empty batch`() {
        postJson("/api/v1/gps-traces/batch", mapOf("points" to (0 until 501).map(::point)))
            .andExpect { status { isBadRequest() } }
        postJson("/api/v1/gps-traces/batch", mapOf("points" to emptyList<Any>()))
            .andExpect { status { isBadRequest() } }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM gps_traces", Int::class.java))

        postJson("/api/v1/gps-traces/batch", mapOf("points" to (0 until 500).map(::point)))
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.accepted") { value(500) } }
    }

    @Test
    fun `requests without api token are rejected`() {
        mockMvc
            .post("/api/v1/commute-trips") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("routeId" to route.id, "tripDate" to "2026-09-21"))
            }.andExpect { status { isUnauthorized() } }
    }

    private fun saveWalkLeg(
        route: CommuteRoute,
        seqOrder: Int,
    ): RouteLeg =
        routeLegRepository.save(
            RouteLeg(
                commuteRoute = route,
                seqOrder = seqOrder,
                legType = LegType.WALK,
                startLat = 37.39,
                startLng = 127.11,
                endLat = 37.391,
                endLng = 127.111,
                plannedDistanceM = 400.0,
            ),
        )

    private fun saveTransitLeg(
        route: CommuteRoute,
        seqOrder: Int,
    ): RouteLeg {
        val line = transitLineRepository.save(TransitLine(mode = TransitMode.BUS, name = "M4403"))
        val boardStop = transitStopRepository.save(TransitStop(TransitMode.BUS, "집앞", 37.391, 127.111))
        val alightStop = transitStopRepository.save(TransitStop(TransitMode.BUS, "회사앞", 37.499, 127.031))
        return routeLegRepository.save(
            RouteLeg(
                commuteRoute = route,
                seqOrder = seqOrder,
                legType = LegType.TRANSIT,
                transitLine = line,
                boardStop = boardStop,
                alightStop = alightStop,
                plannedTravelSec = 2400,
            ),
        )
    }

    private fun createTrip(): Long =
        postJson("/api/v1/commute-trips", mapOf("routeId" to route.id, "tripDate" to "2026-09-21"))
            .andExpect { status { isCreated() } }
            .json()["id"]
            .asLong()

    private fun createTripLeftAt(leftHomeAt: String): Long =
        postJson(
            "/api/v1/commute-trips",
            mapOf(
                "routeId" to route.id,
                "tripDate" to "2026-09-21",
                "leftHomeAt" to leftHomeAt,
            ),
        ).andExpect { status { isCreated() } }
            .json()["id"]
            .asLong()

    private fun tripCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM commute_trips", Int::class.java)!!

    private fun attemptCount(): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM boarding_attempts", Int::class.java)!!

    private fun getTrips(): JsonNode =
        mockMvc
            .get("/api/v1/commute-trips?routeId=${route.id}") { header(ApiTokenFilter.HEADER, properties.apiToken) }
            .andExpect { status { isOk() } }
            .json()

    private fun point(index: Int): Map<String, Any> =
        mapOf(
            "recordedAt" to BASE.plusSeconds(index * 10L).toString(),
            "lat" to 37.39 + index * 0.0001,
            "lng" to 127.11,
            "speedMps" to 1.0,
            "accuracyM" to 10.0,
        )

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
        val BASE: Instant = Instant.parse("2026-09-20T22:30:00Z")
    }
}
