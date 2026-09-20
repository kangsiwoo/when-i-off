package com.kangsiwoo.whenioff.external.tago

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.tago.bus.TagoArrival
import com.kangsiwoo.whenioff.external.tago.bus.TagoArrivalApi
import com.kangsiwoo.whenioff.external.tago.bus.TagoBusRouteApi
import com.kangsiwoo.whenioff.external.tago.bus.TagoRouteStop
import com.kangsiwoo.whenioff.support.Fixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
                TagoCallCounter(SimpleMeterRegistry()),
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

    @Test
    fun `getRouteNoList items map route fields`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_ok.json"))

        val routes = routeApi.getRouteNoList(CITY, "1001")

        assertEquals(listOf("GGB1001", "GGB1001A"), routes.map { it.routeId })
        assertEquals("1001", routes[0].routeNo)
        assertEquals("직행좌석버스", routes[0].routeTp)
        assertEquals("동탄역", routes[0].startNodeNm)
        assertEquals("병점역", routes[0].endNodeNm)
        assertTrue(server.takeRequest().path!!.contains("cityCode=$CITY&routeNo=1001"))
    }

    @Test
    fun `getRouteAcctoThrghSttnList items expose order direction and coordinates`() {
        server.enqueue(Fixtures.json("tago/getRouteAcctoThrghSttnList_ok.json"))

        val stops = routeApi.getRouteAcctoThrghSttnList(CITY, "GGB1001")

        assertEquals((1..6).toList(), stops.map { it.seqNo })
        assertEquals(37.2, stops.first().lat)
        assertEquals(127.07, stops.first().lng)
        assertEquals("동탄역", stops.first().nodeNm)
        assertEquals(listOf("0"), stops.map { it.updownCd }.distinct())
        assertTrue(server.takeRequest().path!!.contains("cityCode=$CITY&routeId=GGB1001"))
    }

    @Test
    fun `arrival items expose arrtime in seconds and vehicle type`() {
        server.enqueue(Fixtures.json("tago/getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList_ok.json"))

        val arrivals = arrivalApi.getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList(CITY, "GGB-S002", "GGB1001")

        assertEquals(listOf(180L, 620L), arrivals.map { it.arrivalInSeconds })
        assertEquals(listOf(2, 9), arrivals.map { it.prevStationCount })
        assertEquals("일반차량", arrivals[0].vehicleTp)
        assertEquals("GGB-S002", arrivals[0].nodeId)
        assertTrue(
            server.takeRequest().path!!.contains("cityCode=$CITY&nodeId=GGB-S002&routeId=GGB1001"),
        )
    }

    @Test
    fun `an empty result yields an empty list`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_empty.json"))

        assertEquals(emptyList(), routeApi.getRouteNoList(CITY, "9999"))
    }

    @Test
    fun `blank numbers parse to null instead of throwing`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_empty.json"))
        val stops = routeApi.getRouteAcctoThrghSttnList(CITY, "GGB1001")

        assertEquals(emptyList(), stops)
        assertNull(TagoRouteStop("n", "", "", "", "", "", "0").seqNo)
        assertNull(TagoArrival("n", "", "", "", "", "", "", "").arrivalInSeconds)
    }

    companion object {
        private const val CITY = "31240"
    }
}
