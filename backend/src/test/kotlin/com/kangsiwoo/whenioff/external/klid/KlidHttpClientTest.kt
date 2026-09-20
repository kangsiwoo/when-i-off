package com.kangsiwoo.whenioff.external.klid

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.support.Fixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.RestClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlidHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: WioProperties.Endpoint
    private lateinit var counter: KlidCallCounter
    private lateinit var client: KlidHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = WioProperties.Endpoint(server.url("/rte").toString(), "dec+oded/key=")
        counter = KlidCallCounter(SimpleMeterRegistry())
        client =
            KlidHttpClient(
                restClient = RestClient.create(),
                objectMapper = jacksonObjectMapper(),
                callCounter = counter,
                maxRetries = 2,
                retryBackoff = Duration.ZERO,
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses a normal envelope and encodes the service key exactly once`() {
        server.enqueue(Fixtures.json("klid/mst_info_ok.json"))

        val page = client.fetchPage(endpoint, "mst_info", "4159000000", 1, 1000)

        assertEquals(2, page.totalCount)
        assertEquals(listOf("HS-101", "HS-999"), page.items.map { it["rteId"] })
        val request = server.takeRequest()
        assertEquals(
            "/rte/mst_info?serviceKey=dec%2Boded%2Fkey%3D&pageNo=1&numOfRows=1000&type=json&stdgCd=4159000000",
            request.path,
        )
        assertEquals(1, counter.todayCount())
    }

    @Test
    fun `an already percent-encoded service key is sent verbatim`() {
        val preEncoded = WioProperties.Endpoint(server.url("/rte").toString(), "abc%2Bdef%3D")
        server.enqueue(Fixtures.json("klid/mst_info_ok.json"))

        client.fetchPage(preEncoded, "mst_info", "4159000000", 1, 1000)

        val request = server.takeRequest()
        assertEquals(
            "/rte/mst_info?serviceKey=abc%2Bdef%3D&pageNo=1&numOfRows=1000&type=json&stdgCd=4159000000",
            request.path,
        )
    }

    @Test
    fun `blank service key fails fast without calling the gateway`() {
        val unconfigured = WioProperties.Endpoint(server.url("/rte").toString(), "")

        assertThrows<KlidNotConfiguredException> { client.fetchAll(unconfigured, "mst_info", "4159000000") }
        assertEquals(0, server.requestCount)
        assertEquals(0, counter.todayCount())
    }

    @Test
    fun `NODATA K3 without body yields an empty list`() {
        server.enqueue(Fixtures.json("klid/tl_drct_info_nodata.json"))

        assertEquals(emptyList(), client.fetchAll(endpoint, "tl_drct_info", "1100000000"))
    }

    @Test
    fun `other K codes raise KlidApiException with the code`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"header":{"resultCode":"K22","resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}}""",
                ),
        )

        val e = assertThrows<KlidApiException> { client.fetchAll(endpoint, "mst_info", "4159000000") }
        assertEquals("K22", e.resultCode)
        assertEquals("LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR", e.resultMsg)
    }

    @Test
    fun `plain text 403 raises KlidGatewayException without retry`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(
                    403,
                ).setHeader("Content-Type", "text/plain")
                .setBody(Fixtures.read("klid/gateway_forbidden.txt")),
        )

        val e = assertThrows<KlidGatewayException> { client.fetchAll(endpoint, "mst_info", "4159000000") }
        assertEquals(403, e.status)
        assertEquals("Forbidden", e.body)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 is retried and the following success is returned`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        server.enqueue(Fixtures.json("klid/mst_info_ok.json"))

        val items = client.fetchAll(endpoint, "mst_info", "4159000000")

        assertEquals(2, items.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `5xx exhausts max retries then raises KlidGatewayException`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error")) }

        val e = assertThrows<KlidGatewayException> { client.fetchAll(endpoint, "mst_info", "4159000000") }
        assertEquals(500, e.status)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `fetchAll pages until totalCount is reached`() {
        server.enqueue(pageOf(totalCount = 1500, pageNo = 1, count = 1000))
        server.enqueue(pageOf(totalCount = 1500, pageNo = 2, count = 500))

        val items = client.fetchAll(endpoint, "ps_info", "4159000000")

        assertEquals(1500, items.size)
        assertEquals("p1-0", items.first()["bstaId"])
        assertEquals("p2-499", items.last()["bstaId"])
        assertTrue(server.takeRequest().path!!.contains("pageNo=1&numOfRows=1000"))
        assertTrue(server.takeRequest().path!!.contains("pageNo=2&numOfRows=1000"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a single item object is accepted as a one-element list`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"header":{"resultCode":"K0","resultMsg":"NORMAL_SERVICE"},
                       "body":{"totalCount":1,"pageNo":1,"numOfRows":1000,"items":{"item":{"rteId":"X","lat":null}}}}""",
                ),
        )

        val items = client.fetchAll(endpoint, "mst_info", "4159000000")

        assertEquals(listOf(mapOf("rteId" to "X", "lat" to "")), items)
    }

    private fun pageOf(
        totalCount: Int,
        pageNo: Int,
        count: Int,
    ): MockResponse {
        val items = (0 until count).joinToString(",") { """{"bstaId":"p$pageNo-$it","bstaSn":"$it"}""" }
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"header":{"resultCode":"K0","resultMsg":"NORMAL_SERVICE"},
                   "body":{"totalCount":$totalCount,"pageNo":$pageNo,"numOfRows":1000,"items":{"item":[$items]}}}""",
            )
    }
}
