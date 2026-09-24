package com.kangsiwoo.whenioff.external.klid

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
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
        signalApi = KlidSignalApi(client, WioProperties.Endpoint(server.url("/rti").toString(), "k"))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `tl_drct_info items keep raw fields and parse totDt`() {
        server.enqueue(Fixtures.json("klid/tl_drct_info_ok.json"))

        val items = signalApi.tlDrctInfo("1100000000").items

        assertEquals(1, items.size)
        assertEquals("1850", items[0].crsrdId)
        assertEquals(Instant.parse("2026-09-10T09:58:19Z"), items[0].observedAt)
        assertEquals(96, items[0].fields.keys.count { it.endsWith("sgRmndCs") || it.endsWith("sgSttsNm") })
    }

    @Test
    fun `crsrd_map_info items parse coordinates`() {
        server.enqueue(Fixtures.json("klid/crsrd_map_info_ok.json"))

        val intersections = signalApi.crsrdMapInfo("1100000000").items

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
