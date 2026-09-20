package com.kangsiwoo.whenioff.external.klid

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.klid.bus.KlidBusApi
import com.kangsiwoo.whenioff.external.klid.signal.KlidSignalApi
import com.kangsiwoo.whenioff.support.Fixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KlidApiFixtureTest {
    private lateinit var server: MockWebServer
    private lateinit var busApi: KlidBusApi
    private lateinit var signalApi: KlidSignalApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client =
            KlidHttpClient(
                RestClient.create(),
                jacksonObjectMapper(),
                KlidCallCounter(SimpleMeterRegistry()),
                0,
                Duration.ZERO,
            )
        busApi = KlidBusApi(client, WioProperties.Endpoint(server.url("/rte").toString(), "k"))
        signalApi = KlidSignalApi(client, WioProperties.Endpoint(server.url("/rti").toString(), "k"))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `rtm_loc_info items expose parsed position speed heading and KST timestamp`() {
        server.enqueue(Fixtures.json("klid/rtm_loc_info_ok.json"))

        val vehicles = busApi.rtmLocInfo("4159000000")

        assertEquals(3, vehicles.size)
        val v1 = vehicles[0]
        assertEquals("경기70아1001", v1.vhclNo)
        assertEquals(37.2015, v1.latitude)
        assertEquals(127.07, v1.longitude)
        assertEquals(30.0, v1.speedKmh)
        assertEquals(0.0, v1.headingDeg)
        assertEquals(Instant.parse("2026-09-19T23:15:30Z"), v1.observedAt)
        assertEquals("GNSS", v1.evtType)
        assertEquals("20260920081530", v1.raw["gthrDt"])
    }

    @Test
    fun `ps_info items expose seq and coordinates`() {
        server.enqueue(Fixtures.json("klid/ps_info_ok.json"))

        val stops = busApi.psInfo("4159000000")

        assertEquals((1..6).toList(), stops.map { it.seqNo })
        assertEquals(37.2, stops.first().lat)
        assertEquals(listOf("0"), stops.map { it.drcGbnCd }.distinct())
    }

    @Test
    fun `mst_info items map route master fields`() {
        server.enqueue(Fixtures.json("klid/mst_info_ok.json"))

        val routes = busApi.mstInfo("4159000000")

        assertEquals("HS-101", routes[0].rteId)
        assertEquals("1001", routes[0].rteNo)
        assertEquals("0500", routes[0].vhclFstTm)
    }

    @Test
    fun `tl_drct_info items keep raw fields and parse totDt`() {
        server.enqueue(Fixtures.json("klid/tl_drct_info_ok.json"))

        val items = signalApi.tlDrctInfo("1100000000")

        assertEquals(1, items.size)
        assertEquals("1850", items[0].crsrdId)
        assertEquals(Instant.parse("2026-09-10T09:58:19Z"), items[0].observedAt)
        assertEquals(96, items[0].fields.keys.count { it.endsWith("sgRmndCs") || it.endsWith("sgSttsNm") })
    }

    @Test
    fun `crsrd_map_info items parse coordinates`() {
        server.enqueue(Fixtures.json("klid/crsrd_map_info_ok.json"))

        val intersections = signalApi.crsrdMapInfo("1100000000")

        assertEquals(listOf("1850", "1851", "1852"), intersections.map { it.crsrdId })
        assertEquals(37.5696, intersections[0].lat)
        assertEquals(126.9773, intersections[0].lng)
    }

    @Test
    fun `blank timestamps parse to null`() {
        assertNull(KlidTime.parseKstOrNull(""))
        assertNull(KlidTime.parseKstOrNull("2026-09-20"))
    }
}
