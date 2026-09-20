package com.kangsiwoo.whenioff.external.tago

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

class TagoHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: WioProperties.Endpoint
    private lateinit var counter: TagoCallCounter
    private lateinit var client: TagoHttpClient

    private val routeParams = mapOf("cityCode" to CITY, "routeNo" to "1001")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = WioProperties.Endpoint(server.url("/BusRouteInfoInqireService").toString(), "dec+oded/key=")
        counter = TagoCallCounter(SimpleMeterRegistry())
        client =
            TagoHttpClient(
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
    fun `parses the response-wrapped envelope and encodes the service key exactly once`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_ok.json"))

        val page = client.fetchPage(endpoint, "getRouteNoList", routeParams, 1, 1000)

        assertEquals(2, page.totalCount)
        assertEquals(listOf("GGB1001", "GGB1001A"), page.items.map { it["routeid"] })
        val request = server.takeRequest()
        assertEquals(
            "/BusRouteInfoInqireService/getRouteNoList" +
                "?serviceKey=dec%2Boded%2Fkey%3D&pageNo=1&numOfRows=1000&_type=json&cityCode=$CITY&routeNo=1001",
            request.path,
        )
        assertEquals(1, counter.todayCount())
    }

    @Test
    fun `an already percent-encoded service key is sent verbatim`() {
        val preEncoded = WioProperties.Endpoint(server.url("/BusRouteInfoInqireService").toString(), "abc%2Bdef%3D")
        server.enqueue(Fixtures.json("tago/getRouteNoList_ok.json"))

        client.fetchPage(preEncoded, "getRouteNoList", routeParams, 1, 1000)

        assertTrue(server.takeRequest().path!!.contains("serviceKey=abc%2Bdef%3D&"))
    }

    @Test
    fun `blank service key fails fast without calling the gateway`() {
        val unconfigured = WioProperties.Endpoint(server.url("/BusRouteInfoInqireService").toString(), "")

        assertThrows<TagoNotConfiguredException> { client.fetchAll(unconfigured, "getRouteNoList", routeParams) }
        assertEquals(0, server.requestCount)
        assertEquals(0, counter.todayCount())
    }

    @Test
    fun `no data comes back as a success code with totalCount 0 and yields an empty list`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_empty.json"))

        assertEquals(emptyList(), client.fetchAll(endpoint, "getRouteNoList", routeParams))
    }

    @Test
    fun `a non-00 result code raises TagoApiException with the code`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"response":{"header":{"resultCode":"22",
                       "resultMsg":"LIMITED NUMBER OF SERVICE REQUESTS EXCEEDS ERROR"}}}""",
                ),
        )

        val e = assertThrows<TagoApiException> { client.fetchAll(endpoint, "getRouteNoList", routeParams) }
        assertEquals("22", e.resultCode)
        assertEquals("LIMITED NUMBER OF SERVICE REQUESTS EXCEEDS ERROR", e.resultMsg)
    }

    @Test
    fun `plain text 403 raises TagoGatewayException without retry`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(
                    403,
                ).setHeader("Content-Type", "text/plain")
                .setBody(Fixtures.read("tago/gateway_forbidden.txt")),
        )

        val e = assertThrows<TagoGatewayException> { client.fetchAll(endpoint, "getRouteNoList", routeParams) }
        assertEquals(403, e.status)
        assertEquals("Forbidden", e.body)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 is retried and the following success is returned`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        server.enqueue(Fixtures.json("tago/getRouteNoList_ok.json"))

        val items = client.fetchAll(endpoint, "getRouteNoList", routeParams)

        assertEquals(2, items.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `5xx exhausts max retries then raises TagoGatewayException`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error")) }

        val e = assertThrows<TagoGatewayException> { client.fetchAll(endpoint, "getRouteNoList", routeParams) }
        assertEquals(500, e.status)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `fetchAll pages until totalCount is reached`() {
        server.enqueue(pageOf(totalCount = 1500, pageNo = 1, count = 1000))
        server.enqueue(pageOf(totalCount = 1500, pageNo = 2, count = 500))

        val items = client.fetchAll(endpoint, "getRouteAcctoThrghSttnList", mapOf("cityCode" to CITY))

        assertEquals(1500, items.size)
        assertEquals("p1-0", items.first()["nodeid"])
        assertEquals("p2-499", items.last()["nodeid"])
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
                    """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                       "body":{"totalCount":1,"pageNo":1,"numOfRows":1000,
                       "items":{"item":{"routeid":"X","routeno":null}}}}}""",
                ),
        )

        val items = client.fetchAll(endpoint, "getRouteNoList", routeParams)

        assertEquals(listOf(mapOf("routeid" to "X", "routeno" to "")), items)
    }

    private fun pageOf(
        totalCount: Int,
        pageNo: Int,
        count: Int,
    ): MockResponse {
        val items = (0 until count).joinToString(",") { """{"nodeid":"p$pageNo-$it","nodeord":"$it"}""" }
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                   "body":{"totalCount":$totalCount,"pageNo":$pageNo,"numOfRows":1000,"items":{"item":[$items]}}}}""",
            )
    }

    companion object {
        private const val CITY = "31240"
    }
}
