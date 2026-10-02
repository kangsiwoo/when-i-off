package com.kangsiwoo.whenioff.external.klid

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.metrics.CallOutcome
import com.kangsiwoo.whenioff.external.metrics.ExternalCallLog
import com.kangsiwoo.whenioff.external.metrics.ExternalCallMetrics
import com.kangsiwoo.whenioff.external.metrics.ExternalSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

private val log = KotlinLogging.logger {}

data class KlidPage(
    val totalCount: Int,
    val pageNo: Int,
    val numOfRows: Int,
    val items: List<Map<String, String>>,
    /** 응답 header의 resultCode가 K3(NODATA)였다. 항목이 0건인 K0 응답과 구분된다. */
    val noData: Boolean = false,
)

/**
 * 한 번의 조회(전 페이지) 결과.
 *
 * [noData]는 게이트웨이가 **K3(NODATA)** 로 답했다는 뜻 — 그 `stdgCd`에는 이 오퍼레이션의 데이터가
 * 아예 제공되지 않는다(#30). `K0`인데 항목이 0건인 것("지금 이 순간 보고가 없다")과는 다르며,
 * 둘을 뭉뚱그리면 커버되는 지자체의 관측을 조용히 잃게 되므로 반드시 구분해서 올린다.
 */
data class KlidResult<T>(
    val items: List<T>,
    val noData: Boolean,
) {
    fun <R> map(transform: (T) -> R): KlidResult<R> = KlidResult(items.map(transform), noData)
}

class KlidHttpClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val metrics: ExternalCallMetrics,
    private val maxRetries: Int,
    private val retryBackoff: Duration,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun fetchAll(
        endpoint: WioProperties.Endpoint,
        op: String,
        stdgCd: String,
    ): KlidResult<Map<String, String>> {
        val all = mutableListOf<Map<String, String>>()
        var pageNo = 1
        var noData = false
        while (true) {
            val page = fetchPage(endpoint, op, stdgCd, pageNo, PAGE_SIZE)
            noData = noData || page.noData
            all += page.items
            if (page.items.isEmpty() || page.items.size < PAGE_SIZE || all.size >= page.totalCount) break
            pageNo++
        }
        return KlidResult(all, noData)
    }

    fun fetchPage(
        endpoint: WioProperties.Endpoint,
        op: String,
        stdgCd: String,
        pageNo: Int,
        numOfRows: Int,
    ): KlidPage {
        if (endpoint.serviceKey.isBlank()) throw KlidNotConfiguredException(endpoint.baseUrl)
        val uri = buildUri(endpoint, op, stdgCd, pageNo, numOfRows)
        val response = executeWithRetry(op, uri)
        val page =
            try {
                parseEnvelope(response)
            } catch (e: KlidException) {
                // 결과 코드를 읽었으면 상태 코드와 상관없이 api_error, 못 읽었으면 2xx만 api_error (CallOutcome 참고)
                val outcome =
                    if (e is KlidApiException ||
                        response.status in 200..299
                    ) {
                        CallOutcome.API_ERROR
                    } else {
                        CallOutcome.HTTP_ERROR
                    }
                metrics.record(SOURCE, op, response.elapsed, outcome)
                ExternalCallLog.warn(log, SOURCE, op, outcome, "status=${response.status} ${e.message}")
                throw e
            }
        metrics.record(SOURCE, op, response.elapsed, CallOutcome.SUCCESS)
        return page
    }

    // data.go.kr keys contain '+', '/', '=': Spring's UriBuilder leaves '+' and '/' raw, which the gateway
    // then decodes as a space, so every value is percent-encoded here exactly once with URLEncoder.
    //
    // The portal issues each key in two forms and the serviceKey may hold either one:
    //  - "Decoding" (raw): base64 alphabet only (A-Za-z0-9+/=), never a literal '%'. Encoded here once.
    //  - "Encoding": already percent-encoded, so it contains '%'. Encoding it again turns "%2B" into
    //    "%252B" and the gateway answers 403 SERVICE_KEY_IS_NOT_REGISTERED_ERROR, so it is passed
    //    through verbatim.
    // A '%' therefore tells the two apart safely. Only serviceKey is special-cased; the remaining
    // values are always encoded.
    //
    // data.go.kr's own portal notice confirms this varies by API/call condition and says to use
    // whichever of the two forms actually works, rather than mandating one — see 공공데이터포털
    // 활용신청 참고사항 1 on the KLID API pages.
    private fun buildUri(
        endpoint: WioProperties.Endpoint,
        op: String,
        stdgCd: String,
        pageNo: Int,
        numOfRows: Int,
    ): URI {
        val query =
            listOf(
                "serviceKey" to encodeServiceKey(endpoint.serviceKey),
                "pageNo" to encode(pageNo.toString()),
                "numOfRows" to encode(numOfRows.toString()),
                "type" to encode("json"),
                "stdgCd" to encode(stdgCd),
            ).joinToString("&") { (k, v) -> "$k=$v" }
        return URI.create("${endpoint.baseUrl.trimEnd('/')}/$op?$query")
    }

    private fun encodeServiceKey(serviceKey: String): String =
        if (serviceKey.contains('%')) serviceKey else encode(serviceKey)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    // 시도마다 시간을 재고, 응답 본문을 해석하지 않고도 실패로 정해지는 시도(응답 없음, 429/5xx)는 여기서 기록한다.
    // 마지막 시도는 봉투를 읽어야 결과가 정해지므로 fetchPage가 기록한다.
    private fun executeWithRetry(
        op: String,
        uri: URI,
    ): RawResponse {
        var attempt = 0
        while (true) {
            val started = nanoTime()
            var ioOutcome = CallOutcome.IO_ERROR
            val outcome =
                try {
                    execute(uri)
                } catch (e: ResourceAccessException) {
                    ioOutcome = CallOutcome.of(e)
                    RawResponse(0, CallOutcome.describe(e))
                }.copy(elapsed = Duration.ofNanos(nanoTime() - started))
            val retryable = outcome.status == 0 || outcome.status == 429 || outcome.status >= 500
            if (!retryable) return outcome
            val failure = if (outcome.status == 0) ioOutcome else CallOutcome.HTTP_ERROR
            metrics.record(SOURCE, op, outcome.elapsed, failure)
            if (attempt >= maxRetries) {
                ExternalCallLog.warn(
                    log,
                    SOURCE,
                    op,
                    failure,
                    "status=${outcome.status} gave up after ${attempt + 1} attempt(s): ${outcome.body.take(200)}",
                )
                throw KlidGatewayException(outcome.status, outcome.body)
            }
            attempt++
            val backoff = retryBackoff.multipliedBy(attempt.toLong())
            ExternalCallLog.warn(
                log,
                SOURCE,
                op,
                failure,
                "status=${outcome.status}; retry $attempt/$maxRetries after ${backoff.toMillis()}ms: " +
                    outcome.body.take(200),
            )
            sleeper(backoff)
        }
    }

    private fun execute(uri: URI): RawResponse =
        restClient
            .get()
            .uri(uri)
            .accept(MediaType.APPLICATION_JSON)
            .exchange { _, response ->
                RawResponse(
                    status = response.statusCode.value(),
                    body = response.body.readAllBytes().toString(StandardCharsets.UTF_8),
                )
            } ?: RawResponse(0, "no response")

    private fun parseEnvelope(response: RawResponse): KlidPage {
        val root =
            try {
                objectMapper.readTree(response.body)
            } catch (e: JsonProcessingException) {
                throw KlidGatewayException(response.status, response.body)
            }
        if (root == null || !root.isObject) throw KlidGatewayException(response.status, response.body)
        val header = root.path("header")
        val resultCode = header.path("resultCode").asText("")
        val resultMsg = header.path("resultMsg").asText("")
        when (resultCode) {
            RESULT_OK -> Unit
            // K3는 에러가 아니다(예외를 올리지 않는다). 다만 "제공되지 않는 지자체"라는 사실은
            // 호출자가 알아야 하므로 빈 목록으로 뭉개지 않고 noData로 표시해 올린다.
            RESULT_NODATA -> return NODATA_PAGE
            else -> throw KlidApiException(resultCode.ifBlank { "HTTP${response.status}" }, resultMsg)
        }
        val body = root.path("body")
        if (body.isMissingNode || body.isNull) return EMPTY_PAGE
        return KlidPage(
            totalCount = body.path("totalCount").asInt(0),
            pageNo = body.path("pageNo").asInt(1),
            numOfRows = body.path("numOfRows").asInt(0),
            items = itemsOf(body.path("items").path("item")),
        )
    }

    private fun itemsOf(itemNode: JsonNode): List<Map<String, String>> {
        val nodes =
            when {
                itemNode.isArray -> itemNode.toList()
                itemNode.isObject -> listOf(itemNode)
                else -> emptyList()
            }
        return nodes.map { node ->
            node.properties().associate { (k, v) -> k to (if (v.isNull) "" else v.asText()) }
        }
    }

    private data class RawResponse(
        val status: Int,
        val body: String,
        val elapsed: Duration = Duration.ZERO,
    )

    companion object {
        private val SOURCE = ExternalSource.KLID
        const val PAGE_SIZE = 1000
        const val RESULT_OK = "K0"
        const val RESULT_NODATA = "K3"
        private val EMPTY_PAGE = KlidPage(0, 1, 0, emptyList())
        private val NODATA_PAGE = KlidPage(0, 1, 0, emptyList(), noData = true)
    }
}
