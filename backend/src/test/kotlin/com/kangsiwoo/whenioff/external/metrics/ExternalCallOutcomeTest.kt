package com.kangsiwoo.whenioff.external.metrics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.klid.KlidApiException
import com.kangsiwoo.whenioff.external.klid.KlidGatewayException
import com.kangsiwoo.whenioff.external.klid.KlidHttpClient
import com.kangsiwoo.whenioff.external.tago.TagoApiException
import com.kangsiwoo.whenioff.external.tago.TagoGatewayException
import com.kangsiwoo.whenioff.external.tago.TagoHttpClient
import com.kangsiwoo.whenioff.support.Fixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.web.client.RestClient
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MockWebServer로 HTTP 시도마다의 outcome 태그를 확인한다 (#76): 성공 / HTTP 오류 / 결과 코드 오류 / 타임아웃 / I/O 오류.
 * 클라이언트는 운영과 같은 방식(`ClientHttpRequestFactoryBuilder.detect()` + 읽기 타임아웃)으로 만든다.
 */
class ExternalCallOutcomeTest {
    private lateinit var server: MockWebServer
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: ExternalCallMetrics
    private lateinit var tago: TagoHttpClient
    private lateinit var klid: KlidHttpClient
    private val appender = ListAppender<ILoggingEvent>()
    private val loggers =
        listOf(TagoHttpClient::class, KlidHttpClient::class).map { LoggerFactory.getLogger(it.qualifiedName) as Logger }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        registry = SimpleMeterRegistry()
        metrics = ExternalCallMetrics(registry, ExternalCallStore(Clock.systemUTC()))
        val restClient =
            RestClient
                .builder()
                .requestFactory(
                    ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings
                            .defaults()
                            .withConnectTimeout(Duration.ofSeconds(2))
                            .withReadTimeout(Duration.ofMillis(300)),
                    ),
                ).build()
        tago = TagoHttpClient(restClient, jacksonObjectMapper(), metrics, maxRetries = 1, retryBackoff = Duration.ZERO)
        klid = KlidHttpClient(restClient, jacksonObjectMapper(), metrics, maxRetries = 1, retryBackoff = Duration.ZERO)
        appender.start()
        loggers.forEach { it.addAppender(appender) }
    }

    @AfterEach
    fun tearDown() {
        loggers.forEach { it.detachAppender(appender) }
        server.shutdown()
    }

    private fun tagoEndpoint() = WioProperties.Endpoint(server.url("/BusRouteInfoInqireService").toString(), "k")

    private fun klidEndpoint() = WioProperties.Endpoint(server.url("/rti").toString(), "k")

    private fun tagoCall() = tago.fetchPage(tagoEndpoint(), "getRouteNoList", mapOf("cityCode" to "31240"), 1, 10)

    private fun count(
        source: String,
        op: String,
        outcome: String,
    ): Long =
        registry
            .find(ExternalCallMetrics.METRIC)
            .tags("source", source, "op", op, "outcome", outcome)
            .timer()
            ?.count() ?: 0

    @Test
    fun `success is one sample tagged success`() {
        server.enqueue(Fixtures.json("tago/getRouteNoList_ok.json"))

        tagoCall()

        assertEquals(1, count("tago", "getRouteNoList", "success"))
        val stats = metrics.store.snapshot().single()
        assertEquals(1, stats.today.calls)
        assertEquals(0, stats.today.failures)
        assertTrue(appender.list.none { it.mdcPropertyMap["outcome"] != null })
    }

    @Test
    fun `5xx is http_error for every attempt including the retry`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable")) }

        assertThrows<TagoGatewayException> { tagoCall() }

        assertEquals(2, server.requestCount)
        assertEquals(2, count("tago", "getRouteNoList", "http_error"))
        assertEquals(2, metrics.todayCount(ExternalSource.TAGO))
        // 재시도 로그 + 포기 로그, 둘 다 source/op/outcome MDC 필드를 싣는다
        val failures = appender.list.filter { it.mdcPropertyMap["outcome"] == "http_error" }
        assertEquals(2, failures.size)
        failures.forEach {
            assertEquals("tago", it.mdcPropertyMap["source"])
            assertEquals("getRouteNoList", it.mdcPropertyMap["op"])
            assertTrue(it.formattedMessage.contains("source=tago op=getRouteNoList outcome=http_error"))
        }
    }

    @Test
    fun `a retried 5xx followed by success counts one failure and one success`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(Fixtures.json("tago/getRouteNoList_ok.json"))

        tagoCall()

        assertEquals(1, count("tago", "getRouteNoList", "http_error"))
        assertEquals(1, count("tago", "getRouteNoList", "success"))
        assertEquals(
            0.5,
            metrics.store
                .snapshot()
                .single()
                .today.failureRate,
        )
    }

    @Test
    fun `an abnormal result code is api_error regardless of the HTTP status`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"response":{"header":{"resultCode":"22","resultMsg":"LIMITED NUMBER OF SERVICE REQUESTS EXCEEDS ERROR"}}}""",
                ),
        )
        // 게이트웨이는 미등록 키를 HTTP 403 + 결과 코드 JSON으로 준다 — 결과 코드를 읽었으므로 api_error
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(Fixtures.read("tago/service_key_not_registered.json")),
        )

        assertThrows<TagoApiException> { tagoCall() }
        assertThrows<TagoApiException> { tagoCall() }

        assertEquals(2, count("tago", "getRouteNoList", "api_error"))
        val log = appender.list.last()
        assertEquals("api_error", log.mdcPropertyMap["outcome"])
        assertTrue(log.formattedMessage.contains("status=403"))
    }

    @Test
    fun `plain-text 403 without a result code is http_error`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody(Fixtures.read("tago/gateway_forbidden.txt")))

        assertThrows<TagoGatewayException> { tagoCall() }

        assertEquals(1, count("tago", "getRouteNoList", "http_error"))
    }

    @Test
    fun `a read timeout is timeout for each attempt`() {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }

        val e = assertThrows<TagoGatewayException> { tagoCall() }

        assertEquals(0, e.status)
        assertEquals(2, count("tago", "getRouteNoList", "timeout"))
        val stats = metrics.store.snapshot().single()
        assertEquals(2, stats.today.failures)
        assertTrue(stats.today.p50Ms!! >= 250, "p50 ${stats.today.p50Ms}")
        // 예외 메시지(=502 detail, 로그)에 요청 URL — serviceKey — 이 실리지 않는다
        assertFalse(e.message!!.contains("serviceKey"))
    }

    @Test
    fun `KLID K3 NODATA is success and a K-code error is api_error`() {
        server.enqueue(Fixtures.json("klid/tl_drct_info_nodata.json"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"header":{"resultCode":"K22","resultMsg":"LIMITED"}}"""),
        )

        klid.fetchPage(klidEndpoint(), "tl_drct_info", "1100000000", 1, 10)
        assertThrows<KlidApiException> { klid.fetchPage(klidEndpoint(), "tl_drct_info", "1100000000", 1, 10) }

        assertEquals(1, count("klid", "tl_drct_info", "success"))
        assertEquals(1, count("klid", "tl_drct_info", "api_error"))
        assertEquals(2, metrics.todayCount(ExternalSource.KLID))
        assertEquals(0, metrics.todayCount(ExternalSource.TAGO))
    }

    @Test
    fun `a refused connection is io_error`() {
        val port = ServerSocket(0).use { it.localPort }
        val closed = WioProperties.Endpoint("http://127.0.0.1:$port/rti", "k")

        val e = assertThrows<KlidGatewayException> { klid.fetchPage(closed, "crsrd_map_info", "1100000000", 1, 10) }

        assertEquals(0, e.status)
        assertEquals(2, count("klid", "crsrd_map_info", "io_error"))
        assertFalse(e.message!!.contains("serviceKey"))
    }
}
