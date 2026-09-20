package com.kangsiwoo.whenioff.ingestion

import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import com.kangsiwoo.whenioff.common.auth.DefaultUser
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
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalStateRepository
import com.kangsiwoo.whenioff.support.Fixtures
import com.kangsiwoo.whenioff.support.KlidFixtureDispatcher
import com.kangsiwoo.whenioff.transit.application.BusPositionSnapshotCache
import com.kangsiwoo.whenioff.transit.application.KlidMasterSyncService
import com.kangsiwoo.whenioff.transit.application.KlidPositionEtaProvider
import com.kangsiwoo.whenioff.transit.application.LegDirectionResolver
import com.kangsiwoo.whenioff.transit.domain.BusPositionObservationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class KlidIngestionIT {
    @Autowired lateinit var masterSync: KlidMasterSyncService

    @Autowired lateinit var intersectionSync: IntersectionSyncService

    @Autowired lateinit var signalIngest: SignalStateIngestService

    @Autowired lateinit var etaProvider: KlidPositionEtaProvider

    @Autowired lateinit var snapshotCache: BusPositionSnapshotCache

    @Autowired lateinit var directionResolver: LegDirectionResolver

    @Autowired lateinit var cycleService: PollingCycleService

    @Autowired lateinit var lineRepository: TransitLineRepository

    @Autowired lateinit var stopRepository: TransitStopRepository

    @Autowired lateinit var positionRepository: BusPositionObservationRepository

    @Autowired lateinit var arrivalRepository: TransitArrivalObservationRepository

    @Autowired lateinit var signalRepository: TrafficSignalRepository

    @Autowired lateinit var stateRepository: TrafficSignalStateRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var routeRepository: CommuteRouteRepository

    @Autowired lateinit var legRepository: RouteLegRepository

    @Autowired lateinit var crossingRepository: RouteLegSignalCrossingRepository

    @Autowired lateinit var mockMvc: MockMvc

    @BeforeEach
    fun resetServer() {
        dispatcher.reset()
        snapshotCache.clear()
    }

    @Test
    fun `syncBusMaster upserts lines stops and line stops idempotently`() {
        val first = masterSync.syncBusMaster(HWASEONG)

        assertEquals(2, first.lines.created)
        assertEquals(6, first.stops.created)
        assertEquals(6, first.lineStops.created)

        val second = masterSync.syncBusMaster(HWASEONG)

        assertEquals(0, second.lines.created + second.lines.updated)
        assertEquals(0, second.stops.created + second.stops.updated)
        assertEquals(0, second.lineStops.created + second.lineStops.updated)
        assertEquals(2, lineRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).size)
        assertEquals(6, stopRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).size)
    }

    @Test
    fun `predict projects vehicles onto the line polyline and persists observations`() {
        masterSync.syncBusMaster(HWASEONG)
        val line = line("HS-101")
        val boardStop = stop("HS-S004")
        val requestsBefore = server.requestCount

        val predictions = etaProvider.predict(line, boardStop, "0")

        assertEquals(1, predictions.size)
        val prediction = predictions.single()
        assertEquals("경기70아1001", prediction.vehicleNo)
        val positionAt = Instant.parse("2026-09-19T23:15:30Z")
        assertTrue(
            prediction.predictedArrivalAt in positionAt.plusSeconds(100)..positionAt.plusSeconds(108),
            "${prediction.predictedArrivalAt}",
        )
        assertEquals(requestsBefore + 1, server.requestCount)

        val positions = positionRepository.findAll().filter { it.transitLine.id == line.id }
        assertEquals(setOf("경기70아1001", "경기70아1002"), positions.map { it.vehicleNo }.toSet())
        assertEquals(30.0, positions.first { it.vehicleNo == "경기70아1001" }.speedKmh)
        assertTrue(positions.first().raw.contains("\"gthrDt\""))
        val arrivals = arrivalRepository.findAll().filter { it.transitLine.id == line.id }
        assertEquals(1, arrivals.size)
        assertEquals(KlidPositionEtaProvider.SOURCE, arrivals.single().source)
        assertEquals(boardStop.id, arrivals.single().stop.id)

        etaProvider.predict(line, boardStop, "0")

        assertEquals(requestsBefore + 1, server.requestCount)
        assertEquals(2, positionRepository.findAll().count { it.transitLine.id == line.id })
        assertEquals(2, arrivalRepository.findAll().count { it.transitLine.id == line.id })
    }

    @Test
    fun `predict returns nothing when the board stop is not on the requested direction`() {
        masterSync.syncBusMaster(HWASEONG)

        assertEquals(emptyList(), etaProvider.predict(line("HS-101"), stop("HS-S004"), "1"))
    }

    @Test
    fun `direction resolver picks the direction where the alight stop follows the board stop`() {
        masterSync.syncBusMaster(HWASEONG)
        val line = line("HS-101")

        assertEquals("0", directionResolver.resolve(line.id!!, stop("HS-S002").id!!, stop("HS-S005").id))
        assertEquals("0", directionResolver.resolve(line.id!!, stop("HS-S002").id!!, null))
        assertNull(directionResolver.resolve(line("HS-999").id!!, stop("HS-S002").id!!, null))
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
            .post(
                "/api/v1/admin/sync/klid/bus-master",
            ) { param("stdgCd", HWASEONG) }
            .andExpect { status { isUnauthorized() } }

        mockMvc
            .post("/api/v1/admin/sync/klid/bus-master") {
                header(ApiTokenFilter.HEADER, "test-token")
                param("stdgCd", HWASEONG)
            }.andExpect {
                status { isOk() }
                jsonPath("$.stdgCd") { value(HWASEONG) }
                jsonPath("$.lines.created") { value(2) }
                jsonPath("$.stops.created") { value(6) }
                jsonPath("$.lineStops.created") { value(6) }
            }

        mockMvc
            .post("/api/v1/admin/sync/klid/intersections") {
                header(ApiTokenFilter.HEADER, "test-token")
                param("stdgCd", SEOUL)
            }.andExpect {
                status { isOk() }
                jsonPath("$.intersections.created") { value(3) }
            }

        mockMvc
            .post("/api/v1/admin/sync/klid/intersections") {
                header(ApiTokenFilter.HEADER, "test-token")
                param("stdgCd", "seoul")
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `gateway errors surface as 502 problem details`() {
        dispatcher.responses["mst_info"] = {
            MockResponse()
                .setResponseCode(
                    403,
                ).setHeader("Content-Type", "text/plain")
                .setBody(Fixtures.read("klid/gateway_forbidden.txt"))
        }

        mockMvc
            .post("/api/v1/admin/sync/klid/bus-master") {
                header(ApiTokenFilter.HEADER, "test-token")
                param("stdgCd", HWASEONG)
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("403")) }
            }
    }

    @Test
    fun `polling cycle fetches each stdgCd once and covers active routes only`() {
        masterSync.syncBusMaster(HWASEONG)
        intersectionSync.syncIntersections(SEOUL)
        val line = line("HS-101")
        val board = stop("HS-S002")
        val alight = stop("HS-S005")
        val signal = signalRepository.findAllByStdgCd(SEOUL).single { it.crsrdId == "1850" }
        val user = userRepository.findById(DefaultUser.ID).orElseThrow()

        val route =
            routeRepository.save(
                CommuteRoute(user, "출근", CommuteDirection.TO_WORK, 37.2000, 127.0700, 37.5696, 126.9773),
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
        val inactive =
            routeRepository.save(
                CommuteRoute(user, "옛 경로", CommuteDirection.TO_HOME, 0.0, 0.0, 0.0, 0.0, isActive = false),
            )
        legRepository.save(
            RouteLeg(
                commuteRoute = inactive,
                seqOrder = 1,
                legType = LegType.TRANSIT,
                transitLine = line("HS-999"),
                boardStop = board,
                alightStop = alight,
                plannedTravelSec = 600,
            ),
        )
        val requestsBefore = server.requestCount

        val result = cycleService.runCycle()

        assertEquals(setOf(HWASEONG), result.busStdgCds)
        assertEquals(1, result.legsPredicted)
        assertEquals(1, result.predictions)
        assertEquals(setOf(SEOUL), result.signalStdgCds)
        assertEquals(6, result.signalStatesInserted)
        assertEquals(emptySet(), result.failedStdgCds)
        assertEquals(requestsBefore + 2, server.requestCount)
        assertTrue(result.klidCallsToday >= 2)
        assertNotNull(arrivalRepository.findAll().singleOrNull { it.stop.id == board.id })
    }

    private fun line(rteId: String): TransitLine =
        lineRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).single { it.externalId == rteId }

    private fun stop(bstaId: String): TransitStop =
        stopRepository.findAllByModeAndStdgCd(TransitMode.BUS, HWASEONG).single { it.externalId == bstaId }

    companion object {
        const val HWASEONG = "4159000000"
        const val SEOUL = "1100000000"

        val dispatcher = KlidFixtureDispatcher()
        val server: MockWebServer =
            MockWebServer().apply {
                dispatcher = this@Companion.dispatcher
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun klidProperties(registry: DynamicPropertyRegistry) {
            registry.add("wio.klid.bus.base-url") { server.url("/rte").toString().trimEnd('/') }
            registry.add("wio.klid.signal.base-url") { server.url("/rti").toString().trimEnd('/') }
            registry.add("wio.klid.bus.service-key") { "test+key/=" }
            registry.add("wio.klid.signal.service-key") { "test+key/=" }
            registry.add("wio.klid.max-retries") { "1" }
            registry.add("wio.klid.retry-backoff") { "1ms" }
        }
    }
}
