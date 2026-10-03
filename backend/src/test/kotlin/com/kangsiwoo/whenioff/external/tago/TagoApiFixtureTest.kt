package com.kangsiwoo.whenioff.external.tago

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.tago.bus.TagoArrival
import com.kangsiwoo.whenioff.external.tago.bus.TagoArrivalApi
import com.kangsiwoo.whenioff.external.tago.bus.TagoBusRouteApi
import com.kangsiwoo.whenioff.external.tago.bus.TagoRouteStop
import com.kangsiwoo.whenioff.support.Fixtures
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagoApiFixtureTest {
    private lateinit var server: MockWebServer
    private lateinit var routeApi: TagoBusRouteApi
    private lateinit var arrivalApi: TagoArrivalApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client =
            TagoHttpClient(
                RestClient.create(),
                jacksonObjectMapper(),
                Fixtures.metrics(),
                0,
                Duration.ZERO,
            )
        routeApi =
            TagoBusRouteApi(client, WioProperties.Endpoint(server.url("/BusRouteInfoInqireService").toString(), "k"))
        arrivalApi =
            TagoArrivalApi(client, WioProperties.Endpoint(server.url("/ArvlInfoInqireService").toString(), "k"))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // fixture는 #17에서 실 키로 받은 응답이다(키 없음, 정류소 목록만 일부 잘라냄 — totalCount도 맞춰 둠).

    @Test
    fun `getRouteNoList maps numeric and string route numbers alike and keeps partial matches`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_ok.json"))

        val routes = routeApi.getRouteNoList(CITY, "4108")

        // 부분일치 검색이라 4108을 찾으면 M4108과 그 예약 노선도 같이 온다. 4108만 JSON 숫자다.
        assertEquals(listOf("M4108(예약)", "M4108", "4108"), routes.map { it.routeNo })
        val route = routes.last()
        assertEquals("GGB233000270", route.routeId)
        assertEquals("직행좌석버스", route.routeTp)
        assertEquals("나루마을.월드반도", route.startNodeNm)
        assertEquals("서울역버스환승센터(6번승강장)(중)", route.endNodeNm)
        assertTrue(server.takeRequest().path!!.contains("cityCode=$CITY&routeNo=4108"))
    }

    @Test
    fun `a single result arrives as an object instead of an array`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_single.json"))

        val routes = routeApi.getRouteNoList(CITY, "6004")

        assertEquals(listOf("GGB234000314"), routes.map { it.routeId })
        assertEquals("6004", routes.single().routeNo)
    }

    @Test
    fun `GGB route stops come without updowncd and include pass-through points`() {
        server.enqueue(Fixtures.json("tago/getRouteAcctoThrghSttnList_ok.json"))

        val stops = routeApi.getRouteAcctoThrghSttnList(CITY, "GGB233000270")

        assertEquals(13, stops.size)
        assertEquals(listOf(1, 2, 3), stops.take(3).map { it.seqNo })
        val first = stops.first()
        assertEquals("GGB233001448", first.nodeId)
        assertEquals("나루마을.월드반도", first.nodeNm)
        assertEquals("36678", first.nodeNo)
        assertEquals(37.1908, first.lat)
        assertEquals(127.0765167, first.lng)
        // 경기 노선은 updowncd를 주지 않는다 → 노선 전체가 한 방향
        assertEquals(setOf(""), stops.map { it.updownCd }.toSet())
        assertEquals(setOf(TagoRouteStop.SINGLE_DIRECTION), stops.map { it.directionCode }.toSet())
        val passThrough = stops.filter { it.isPassThrough }
        assertEquals(listOf(2, 8, 54), passThrough.map { it.seqNo })
        assertTrue(passThrough.all { it.nodeNo.isEmpty() })
        assertTrue(server.takeRequest().path!!.contains("cityCode=$CITY&routeId=GGB233000270"))
    }

    @Test
    fun `route stops with numeric updowncd keep both directions`() {
        server.enqueue(Fixtures.json("tago/getRouteAcctoThrghSttnList_updowncd.json"))

        val stops = routeApi.getRouteAcctoThrghSttnList("25", "DJB30300037")

        assertEquals(listOf("0", "1"), stops.map { it.directionCode }.distinct())
        assertEquals((45..52).toList(), stops.map { it.seqNo })
    }

    @Test
    fun `arrival items expose arrtime in seconds and vehicle type`() {
        server.enqueue(Fixtures.json("tago/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList_ok.json"))

        val arrivals =
            arrivalApi.getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList(CITY, "GGB233001282", "GGB233000270")

        assertEquals(listOf(5207L, 5876L), arrivals.map { it.arrivalInSeconds })
        assertEquals(listOf(37, 43), arrivals.map { it.prevStationCount })
        assertEquals("일반차량", arrivals[0].vehicleTp)
        assertEquals("GGB233001282", arrivals[0].nodeId)
        assertEquals("4108", arrivals[0].routeNo)
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/ArvlInfoInqireService/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList?"))
        assertTrue(path.contains("cityCode=$CITY&nodeId=GGB233001282&routeId=GGB233000270"))
    }

    @Test
    fun `a single arriving bus arrives as an object`() {
        server.enqueue(Fixtures.json("tago/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList_single.json"))

        val arrivals =
            arrivalApi.getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList(CITY, "GGB233003128", "GGB233000327")

        assertEquals(listOf(377L), arrivals.map { it.arrivalInSeconds })
        assertEquals("G6010", arrivals.single().routeNo)
    }

    @Test
    fun `no bus on the way comes back as an empty items string`() {
        server.enqueue(Fixtures.json("tago/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList_empty.json"))

        assertEquals(
            emptyList(),
            arrivalApi.getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList(CITY, "GGB101000006", "GGB233000270"),
        )
    }

    @Test
    fun `an empty result yields an empty list`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_empty.json"))

        assertEquals(emptyList(), routeApi.getRouteNoList(CITY, "1112"))
    }

    @Test
    fun `blank numbers parse to null instead of throwing`() {
        assertNull(TagoRouteStop("n", "", "", "", "", "", "").seqNo)
        assertNull(TagoArrival("n", "", "", "", "", "", "", "").arrivalInSeconds)
    }

    companion object {
        private const val CITY = "31240"
    }
}
