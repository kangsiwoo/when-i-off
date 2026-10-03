package com.kangsiwoo.whenioff.ingestion

import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.klid.KlidNoDataRegistry
import com.kangsiwoo.whenioff.external.tago.bus.TagoRouteStop
import com.kangsiwoo.whenioff.polling.PollingCycleService
import com.kangsiwoo.whenioff.route.domain.CommuteDirection
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossing
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossingRepository
import com.kangsiwoo.whenioff.signal.application.IntersectionSyncService
import com.kangsiwoo.whenioff.signal.application.SignalStateIngestService
import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalStateRepository
import com.kangsiwoo.whenioff.support.Fixtures
import com.kangsiwoo.whenioff.support.PublicDataFixtureDispatcher
import com.kangsiwoo.whenioff.transit.application.LegDirectionResolver
import com.kangsiwoo.whenioff.transit.application.TagoArrivalPredictionProvider
import com.kangsiwoo.whenioff.transit.application.TagoMasterSyncService
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLineStopRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import com.kangsiwoo.whenioff.user.domain.UserRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TAGO(버스) + KLID(신호등) 수집 경로를 MockWebServer fixture로 함께 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IngestionIT {
    @Autowired lateinit var masterSync: TagoMasterSyncService

    @Autowired lateinit var intersectionSync: IntersectionSyncService

    @Autowired lateinit var signalIngest: SignalStateIngestService

    @Autowired lateinit var arrivalProvider: TagoArrivalPredictionProvider

    @Autowired lateinit var directionResolver: LegDirectionResolver

    @Autowired lateinit var cycleService: PollingCycleService

    @Autowired lateinit var lineRepository: TransitLineRepository

    @Autowired lateinit var stopRepository: TransitStopRepository

    @Autowired lateinit var lineStopRepository: TransitLineStopRepository

    @Autowired lateinit var arrivalRepository: TransitArrivalObservationRepository

    @Autowired lateinit var signalRepository: TrafficSignalRepository

    @Autowired lateinit var stateRepository: TrafficSignalStateRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var routeRepository: CommuteRouteRepository

    @Autowired lateinit var legRepository: RouteLegRepository

    @Autowired lateinit var crossingRepository: RouteLegSignalCrossingRepository

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var properties: WioProperties

    @Autowired lateinit var noDataRegistry: KlidNoDataRegistry

    @BeforeEach
    fun resetServer() {
        dispatcher.reset()
        // 레지스트리는 싱글턴이라 @Transactional 롤백으로 지워지지 않는다. 테스트마다 비워 둔다.
        noDataRegistry.clear()
    }

    @Test
    fun `syncBusRoute upserts lines stops and line stops idempotently`() {
        val first = masterSync.syncBusRoute(HWASEONG, ROUTE_NO)

        // 부분일치 검색 결과의 M4108·M4108(예약)은 번호가 정확히 같지 않아 제외된다.
        assertEquals(listOf(ROUTE_ID), first.routeIds)
        assertEquals(1, first.lines.created)
        // 정류소 13개 중 미정차 통과 지점 3개는 정류장도, 노선-정류장도 만들지 않는다.
        assertEquals(10, first.stops.created)
        assertEquals(3, first.stops.skipped)
        assertEquals(10, first.lineStops.created)
        assertEquals(3, first.lineStops.skipped)
        // 경기 노선은 updowncd가 없어 노선 전체가 한 방향이다.
        assertEquals(
            setOf(TagoRouteStop.SINGLE_DIRECTION),
            lineStopRepository.findAllByTransitLineIn(listOf(line(ROUTE_ID))).map { it.directionCode }.toSet(),
        )

        val second = masterSync.syncBusRoute(HWASEONG, ROUTE_NO)

        assertEquals(0, second.lines.created + second.lines.updated)
        assertEquals(0, second.stops.created + second.stops.updated)
        assertEquals(0, second.lineStops.created + second.lineStops.updated)
        assertEquals(1, lineRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).size)
        assertEquals(10, stopRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).size)
    }

    @Test
    fun `syncBusRoute registers every route sharing the same route number`() {
        // 화성·성남 실 데이터(2026-10)에는 번호가 완전히 같은 노선이 없어 이 경우만 합성 응답을 쓴다.
        dispatcher.responses["getRouteNoList"] = {
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},""" +
                        """"body":{"items":{"item":[""" +
                        """{"routeid":"$ROUTE_ID","routeno":4108,"routetp":"직행좌석버스"},""" +
                        """{"routeid":"GGB233000999","routeno":"4108","routetp":"일반버스"}""" +
                        """]},"numOfRows":1000,"pageNo":1,"totalCount":2}}}""",
                )
        }

        val result = masterSync.syncBusRoute(HWASEONG, ROUTE_NO)

        assertEquals(listOf(ROUTE_ID, "GGB233000999"), result.routeIds.sorted())
        assertEquals(2, result.lines.created)
        assertEquals(10, result.stops.created)
        assertEquals(20, result.lineStops.created)
    }

    @Test
    fun `syncBusRoute registers nothing when only partial matches come back and lists them`() {
        val result = masterSync.syncBusRoute(HWASEONG, "108")

        assertEquals(emptyList(), result.routeIds)
        assertEquals(listOf("M4108(예약)", "M4108", "4108"), result.candidates)
        assertEquals(0, lineRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).size)
    }

    @Test
    fun `syncBusRoute returns no routeIds when the number matches nothing`() {
        dispatcher.responses["getRouteNoList"] = { Fixtures.json("tago/getRouteNoList_empty.json") }

        val result = masterSync.syncBusRoute(HWASEONG, "9999")

        assertEquals(emptyList(), result.routeIds)
        assertEquals(0, result.lines.created)
    }

    @Test
    fun `predict turns arrtime into a predicted arrival and stores an observation without a vehicle number`() {
        masterSync.syncBusRoute(HWASEONG, ROUTE_NO)
        val line = line(ROUTE_ID)
        val boardStop = stop(BOARD_NODE)
        val requestsBefore = server.requestCount

        val predictions = arrivalProvider.predict(line, boardStop, "0")

        assertEquals(2, predictions.size)
        assertEquals(listOf(null, null), predictions.map { it.vehicleNo })
        val observedAt = predictions.first().observedAt
        assertEquals(
            listOf(observedAt.plusSeconds(5207), observedAt.plusSeconds(5876)),
            predictions.map { it.predictedArrivalAt },
        )
        assertEquals(requestsBefore + 1, server.requestCount)

        val arrivals = arrivalRepository.findAll().filter { it.transitLine.id == line.id }
        assertEquals(2, arrivals.size)
        assertEquals(setOf(TagoArrivalPredictionProvider.SOURCE), arrivals.map { it.source }.toSet())
        assertEquals(setOf(boardStop.id), arrivals.map { it.stop.id }.toSet())
        assertTrue(arrivals.all { it.vehicleNo == null })
    }

    @Test
    fun `predict returns nothing when the line or stop has no TAGO id`() {
        masterSync.syncBusRoute(HWASEONG, ROUTE_NO)
        val line = line(ROUTE_ID)
        val boardStop = stop(BOARD_NODE)
        val requestsBefore = server.requestCount
        line.externalId = null

        assertEquals(emptyList(), arrivalProvider.predict(line, boardStop, "0"))
        assertEquals(requestsBefore, server.requestCount)
    }

    @Test
    fun `direction resolver picks the direction where the alight stop follows the board stop`() {
        masterSync.syncBusRoute(HWASEONG, ROUTE_NO)
        val line = line(ROUTE_ID)

        assertEquals("0", directionResolver.resolve(line.id!!, stop(BOARD_NODE).id!!, stop(ALIGHT_NODE).id))
        assertEquals("0", directionResolver.resolve(line.id!!, stop(BOARD_NODE).id!!, null))
        // 경유 정류소가 등록되지 않은 노선이면 방향을 못 고른다.
        assertNull(directionResolver.resolve(UNKNOWN_LINE_ID, stop(BOARD_NODE).id!!, null))
    }

    @Test
    fun `syncIntersections then ingest signal states writes one row per record and ignores duplicates`() {
        val sync = intersectionSync.syncIntersections(SEOUL)
        assertEquals(3, sync.intersections.created)
        assertEquals(0, intersectionSync.syncIntersections(SEOUL).intersections.created)

        val first = signalIngest.ingest(SEOUL)

        assertEquals(1, first.intersectionsMatched)
        assertEquals(6, first.statesInserted)
        assertEquals(0, first.statesDuplicate)

        val second = signalIngest.ingest(SEOUL)

        assertEquals(0, second.statesInserted)
        assertEquals(6, second.statesDuplicate)

        val signal = signalRepository.findAllByStdgCd(SEOUL).single { it.crsrdId == "1850" }
        val states = stateRepository.findAllByTrafficSignalIdOrderByObservedAtDesc(signal.id!!)
        assertEquals(6, states.size)
        val stPd = states.single { it.approachDir == "st" && it.signalKind == "Pd" }
        assertEquals("stop-And-Remain", stPd.status)
        assertNull(stPd.remainingDs)
        assertEquals(150, states.single { it.approachDir == "nt" && it.signalKind == "Pd" }.remainingDs)
        assertEquals(Instant.parse("2026-09-10T09:58:19Z"), stPd.observedAt)
    }

    @Test
    fun `ingest with NODATA inserts nothing`() {
        intersectionSync.syncIntersections(SEOUL)
        dispatcher.responses["tl_drct_info"] = { Fixtures.json("klid/tl_drct_info_nodata.json") }

        val result = signalIngest.ingest(SEOUL)

        assertEquals(0, result.intersectionsFetched)
        assertEquals(0, result.statesInserted)
    }

    @Test
    fun `admin sync endpoints are token protected and return counts`() {
        mockMvc
            .post("/api/v1/admin/sync/tago/bus-route") {
                param("cityCode", HWASEONG)
                param("routeNo", ROUTE_NO)
            }.andExpect { status { isUnauthorized() } }

        mockMvc
            .post("/api/v1/admin/sync/tago/bus-route") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                param("cityCode", HWASEONG)
                param("routeNo", ROUTE_NO)
            }.andExpect {
                status { isOk() }
                jsonPath("$.cityCode") { value(HWASEONG) }
                jsonPath("$.routeIds[0]") { value(ROUTE_ID) }
                jsonPath("$.lines.created") { value(1) }
                jsonPath("$.stops.created") { value(10) }
                jsonPath("$.lineStops.created") { value(10) }
            }

        mockMvc
            .post("/api/v1/admin/sync/klid/intersections") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                param("stdgCd", SEOUL)
            }.andExpect {
                status { isOk() }
                jsonPath("$.intersections.created") { value(3) }
            }

        mockMvc
            .post("/api/v1/admin/sync/klid/intersections") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                param("stdgCd", "seoul")
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `bus-route answers 404 when TAGO has no route with that number`() {
        dispatcher.responses["getRouteNoList"] = { Fixtures.json("tago/getRouteNoList_empty.json") }

        mockMvc
            .post("/api/v1/admin/sync/tago/bus-route") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                param("cityCode", HWASEONG)
                param("routeNo", "9999")
            }.andExpect { status { isNotFound() } }

        // 부분일치로만 걸린 노선은 등록하지 않고 번호를 안내한다.
        dispatcher.responses["getRouteNoList"] = { Fixtures.json("tago/getRouteNoList_ok.json") }
        mockMvc
            .post("/api/v1/admin/sync/tago/bus-route") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                param("cityCode", HWASEONG)
                param("routeNo", "108")
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("similar: M4108(예약), M4108, 4108")) }
            }
    }

    @Test
    fun `TAGO gateway errors surface as 502 with tagoResultCode`() {
        dispatcher.responses["getRouteNoList"] = {
            MockResponse()
                .setResponseCode(
                    403,
                ).setHeader("Content-Type", "text/plain")
                .setBody(Fixtures.read("tago/gateway_forbidden.txt"))
        }

        mockMvc
            .post("/api/v1/admin/sync/tago/bus-route") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                param("cityCode", HWASEONG)
                param("routeNo", ROUTE_NO)
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("403")) }
                jsonPath("$.tagoResultCode") { value("HTTP403") }
            }
    }

    @Test
    fun `KLID result codes surface as 502 with klidResultCode`() {
        dispatcher.responses["crsrd_map_info"] = {
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"header":{"resultCode":"K22","resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}}""",
                )
        }

        mockMvc
            .post("/api/v1/admin/sync/klid/intersections") {
                header(ApiTokenFilter.HEADER, properties.apiToken)
                param("stdgCd", SEOUL)
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.klidResultCode") { value("K22") }
            }
    }

    @Test
    fun `polling cycle covers active routes only and calls TAGO once per leg`() {
        masterSync.syncBusRoute(HWASEONG, ROUTE_NO)
        intersectionSync.syncIntersections(SEOUL)
        val line = line(ROUTE_ID)
        val board = stop(BOARD_NODE)
        val alight = stop(ALIGHT_NODE)
        val signal = signalRepository.findAllByStdgCd(SEOUL).single { it.crsrdId == "1850" }
        seedActiveRoute(line, board, alight, signal)
        val inactive =
            routeRepository.save(
                CommuteRoute(user(), "옛 경로", CommuteDirection.TO_HOME, 0.0, 0.0, 0.0, 0.0, isActive = false),
            )
        legRepository.save(
            RouteLeg(
                commuteRoute = inactive,
                seqOrder = 1,
                legType = LegType.TRANSIT,
                transitLine = line,
                boardStop = board,
                alightStop = alight,
                plannedTravelSec = 600,
            ),
        )
        val requestsBefore = server.requestCount

        val result = cycleService.runCycle()

        assertEquals(setOf(HWASEONG), result.busCityCodes)
        assertEquals(1, result.legsPredicted)
        assertEquals(2, result.predictions)
        assertEquals(setOf(SEOUL), result.signalStdgCds)
        assertEquals(emptySet(), result.signalSkippedStdgCds)
        assertEquals(6, result.signalStatesInserted)
        assertEquals(emptySet(), result.failedCodes)
        assertEquals(requestsBefore + 2, server.requestCount)
        assertTrue(result.tagoCallsToday >= 1)
        assertTrue(result.klidCallsToday >= 1)
        assertNotNull(arrivalRepository.findAll().firstOrNull { it.stop.id == board.id })
    }

    @Test
    fun `polling cycle marks a failed bus cityCode and still ingests signals`() {
        masterSync.syncBusRoute(HWASEONG, ROUTE_NO)
        intersectionSync.syncIntersections(SEOUL)
        val signal = signalRepository.findAllByStdgCd(SEOUL).single { it.crsrdId == "1850" }
        seedActiveRoute(line(ROUTE_ID), stop(BOARD_NODE), stop(ALIGHT_NODE), signal)
        dispatcher.responses["getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList"] = {
            MockResponse().setResponseCode(500).setBody("Internal Server Error")
        }

        val result = cycleService.runCycle()

        assertEquals(setOf(HWASEONG), result.failedCodes)
        assertEquals(0, result.legsPredicted)
        assertEquals(setOf(SEOUL), result.signalStdgCds)
        assertEquals(6, result.signalStatesInserted)
    }

    @Test
    fun `polling cycle stops calling a stdgCd that answered K3 and reports it as skipped`() {
        intersectionSync.syncIntersections(SEOUL)
        seedSignalOnlyRoute(signalAt("1850"))
        dispatcher.responses["tl_drct_info"] = { Fixtures.json("klid/tl_drct_info_nodata.json") }

        // 첫 사이클은 실제로 불러 봐야 K3인 줄 안다.
        val first = cycleService.runCycle()

        assertEquals(setOf(SEOUL), first.signalStdgCds)
        assertEquals(emptySet(), first.signalSkippedStdgCds)
        assertEquals(0, first.signalStatesInserted)
        assertTrue(noDataRegistry.isMarked(SEOUL))

        val requestsBefore = server.requestCount
        val second = cycleService.runCycle()

        assertEquals(requestsBefore, server.requestCount)
        assertEquals(setOf(SEOUL), second.signalStdgCds)
        assertEquals(setOf(SEOUL), second.signalSkippedStdgCds)
        assertEquals(0, second.signalStatesInserted)
        assertEquals(emptySet(), second.failedCodes)
    }

    /**
     * 핵심: `K0` + 0건은 "지금 이 순간 보고가 없다"는 일시적 상태일 뿐이다. 이걸 NODATA로 오인해
     * 건너뛰면 실제로 커버되는 지자체의 관측을 TTL 동안 조용히 잃는다 (#31).
     */
    @Test
    fun `polling cycle keeps calling a stdgCd that answered K0 with zero items`() {
        intersectionSync.syncIntersections(SEOUL)
        seedSignalOnlyRoute(signalAt("1850"))
        dispatcher.responses["tl_drct_info"] = { Fixtures.json("klid/tl_drct_info_empty.json") }

        val first = cycleService.runCycle()

        assertEquals(emptySet(), first.signalSkippedStdgCds)
        assertFalse(noDataRegistry.isMarked(SEOUL))

        val requestsBefore = server.requestCount
        val second = cycleService.runCycle()

        assertEquals(requestsBefore + 1, server.requestCount)
        assertEquals(setOf(SEOUL), second.signalStdgCds)
        assertEquals(emptySet(), second.signalSkippedStdgCds)
        assertFalse(noDataRegistry.isMarked(SEOUL))
    }

    @Test
    fun `polling cycle never skips a stdgCd that returns data`() {
        intersectionSync.syncIntersections(SEOUL)
        seedSignalOnlyRoute(signalAt("1850"))

        val first = cycleService.runCycle()

        assertEquals(6, first.signalStatesInserted)
        assertFalse(noDataRegistry.isMarked(SEOUL))

        val requestsBefore = server.requestCount
        val second = cycleService.runCycle()

        assertEquals(requestsBefore + 1, server.requestCount)
        assertEquals(emptySet(), second.signalSkippedStdgCds)
        assertFalse(noDataRegistry.isMarked(SEOUL))
    }

    private fun user() = userRepository.findById(DefaultUser.ID).orElseThrow()

    private fun seedActiveRoute(
        line: TransitLine,
        board: TransitStop,
        alight: TransitStop,
        signal: TrafficSignal,
    ) {
        val route =
            routeRepository.save(
                CommuteRoute(user(), "출근", CommuteDirection.TO_WORK, 37.2000, 127.0700, 37.5696, 126.9773),
            )
        val walk =
            legRepository.save(
                RouteLeg(
                    commuteRoute = route,
                    seqOrder = 1,
                    legType = LegType.WALK,
                    startLat = 37.2000,
                    startLng = 127.0690,
                    endLat = 37.2030,
                    endLng = 127.0700,
                    plannedDistanceM = 400.0,
                ),
            )
        legRepository.save(
            RouteLeg(
                commuteRoute = route,
                seqOrder = 2,
                legType = LegType.TRANSIT,
                transitLine = line,
                boardStop = board,
                alightStop = alight,
                plannedTravelSec = 600,
            ),
        )
        crossingRepository.save(RouteLegSignalCrossing(walk, signal, 1, "nt", "Pd"))
    }

    /** KLID 호출만 일어나는 최소 경로 — 신호 폴링의 호출 수를 그대로 셀 수 있다. */
    private fun seedSignalOnlyRoute(signal: TrafficSignal) {
        val route =
            routeRepository.save(
                CommuteRoute(user(), "도보만", CommuteDirection.TO_WORK, 37.5690, 126.9770, 37.5700, 126.9780),
            )
        val walk =
            legRepository.save(
                RouteLeg(
                    commuteRoute = route,
                    seqOrder = 1,
                    legType = LegType.WALK,
                    startLat = 37.5690,
                    startLng = 126.9770,
                    endLat = 37.5700,
                    endLng = 126.9780,
                    plannedDistanceM = 200.0,
                ),
            )
        crossingRepository.save(RouteLegSignalCrossing(walk, signal, 1, "nt", "Pd"))
    }

    private fun signalAt(crsrdId: String): TrafficSignal =
        signalRepository.findAllByStdgCd(SEOUL).single { it.crsrdId == crsrdId }

    private fun line(routeId: String): TransitLine =
        lineRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).single { it.externalId == routeId }

    private fun stop(nodeId: String): TransitStop =
        stopRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).single { it.externalId == nodeId }

    companion object {
        /** TAGO cityCode (KLID stdgCd와 코드 체계가 다르다 — fixture 값). */
        const val HWASEONG = "31240"
        const val ROUTE_NO = "4108"

        /** 아래 값은 fixture(#17 실 응답): 4108 직행좌석, 동탄 방면 예당마을.롯데캐슬(55) → 나루마을(60). */
        const val ROUTE_ID = "GGB233000270"
        const val BOARD_NODE = "GGB233001282"
        const val ALIGHT_NODE = "GGB233001713"
        const val SEOUL = "1100000000"
        const val UNKNOWN_LINE_ID = -1L

        val dispatcher = PublicDataFixtureDispatcher()
        val server: MockWebServer =
            MockWebServer().apply {
                dispatcher = this@Companion.dispatcher
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun externalApiProperties(registry: DynamicPropertyRegistry) {
            registry.add("wio.tago.route-info-base-url") {
                server.url("/BusRouteInfoInqireService").toString().trimEnd('/')
            }
            registry.add("wio.tago.arrival-info-base-url") {
                server.url("/ArvlInfoInqireService").toString().trimEnd('/')
            }
            registry.add("wio.tago.service-key") { "test+key/=" }
            registry.add("wio.tago.max-retries") { "1" }
            registry.add("wio.tago.retry-backoff") { "1ms" }
            registry.add("wio.klid.signal.base-url") { server.url("/rti").toString().trimEnd('/') }
            registry.add("wio.klid.signal.service-key") { "test+key/=" }
            registry.add("wio.klid.max-retries") { "1" }
            registry.add("wio.klid.retry-backoff") { "1ms" }
        }
    }
}
