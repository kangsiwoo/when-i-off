package com.kangsiwoo.whenioff.external.tago

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

data class TagoPage(
    val totalCount: Int,
    val pageNo: Int,
    val numOfRows: Int,
    val items: List<Map<String, String>>,
)

/**
 * TAGO(국토교통부 전국버스) 게이트웨이 클라이언트.
 *
 * KLID(`KlidHttpClient`)와 게이트웨이/인증 방식은 같지만 봉투 구조가 다르다:
 *  - TAGO: `{"response":{"header":{resultCode,resultMsg},"body":{totalCount,pageNo,numOfRows,"items":{"item":[…]}}}}`
 *    (`response` 래퍼가 있고, 정상 코드는 `"00"`)
 *  - KLID: `response` 래퍼 없음, 정상 코드는 `"K0"`, NODATA는 `"K3"`
 * TAGO에는 KLID의 `K3` 같은 NODATA 코드가 없고, 데이터가 없으면 정상 코드에 `totalCount=0`으로 온다.
 * 응답 포맷 파라미터 이름도 KLID의 `type`이 아니라 `_type`이다.
 */
class TagoHttpClient(
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
        params: Map<String, String>,
    ): List<Map<String, String>> {
        val all = mutableListOf<Map<String, String>>()
        var pageNo = 1
        while (true) {
            val page = fetchPage(endpoint, op, params, pageNo, PAGE_SIZE)
            all += page.items
            if (page.items.isEmpty() || page.items.size < PAGE_SIZE || all.size >= page.totalCount) break
            pageNo++
        }
        return all
    }

    fun fetchPage(
        endpoint: WioProperties.Endpoint,
        op: String,
        params: Map<String, String>,
        pageNo: Int,
        numOfRows: Int,
    ): TagoPage {
        if (endpoint.serviceKey.isBlank()) throw TagoNotConfiguredException(endpoint.baseUrl)
        val uri = buildUri(endpoint, op, params, pageNo, numOfRows)
        val response = executeWithRetry(op, uri)
        val page =
            try {
                parseEnvelope(response)
            } catch (e: TagoException) {
                // 결과 코드를 읽었으면 상태 코드와 상관없이 api_error, 못 읽었으면 2xx만 api_error (CallOutcome 참고)
                val outcome =
                    if (e is TagoApiException ||
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
    // This is a data.go.kr platform-wide quirk, not a KLID/TAGO specific one — the portal's own notice
    // says the key form that actually works varies by API/call condition (공공데이터포털 활용신청 참고사항 1).
    private fun buildUri(
        endpoint: WioProperties.Endpoint,
        op: String,
        params: Map<String, String>,
        pageNo: Int,
        numOfRows: Int,
    ): URI {
        val query =
            (
                listOf(
                    "serviceKey" to encodeServiceKey(endpoint.serviceKey),
                    "pageNo" to encode(pageNo.toString()),
                    "numOfRows" to encode(numOfRows.toString()),
                    "_type" to encode("json"),
                ) + params.map { (k, v) -> k to encode(v) }
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
                throw TagoGatewayException(outcome.status, outcome.body)
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

    private fun parseEnvelope(response: RawResponse): TagoPage {
        val root =
            try {
                objectMapper.readTree(response.body)
            } catch (e: JsonProcessingException) {
                throw TagoGatewayException(response.status, response.body)
            }
        if (root == null || !root.isObject) throw TagoGatewayException(response.status, response.body)
        // 인증/등록 오류는 정상 봉투가 아니라 게이트웨이 자체 형식으로 온다 (실 키로 확인한 형태):
        // {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{errMsg,returnAuthMsg,returnReasonCode}}}
        // 이걸 따로 읽지 않으면 "등록되지 않은 서비스키"가 그냥 HTTP403으로 뭉개져 원인 파악이 어렵다.
        val cmmMsgHeader = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader")
        if (cmmMsgHeader.isObject) {
            val errMsg = cmmMsgHeader.path("errMsg").asText("")
            val authMsg = cmmMsgHeader.path("returnAuthMsg").asText("")
            throw TagoApiException(
                cmmMsgHeader.path("returnReasonCode").asText("").ifBlank { "HTTP${response.status}" },
                listOf(errMsg, authMsg).filter { it.isNotBlank() }.joinToString(" / "),
            )
        }
        val envelope = root.path("response").takeIf { it.isObject } ?: root
        val header = envelope.path("header")
        val resultCode = header.path("resultCode").asText("")
        val resultMsg = header.path("resultMsg").asText("")
        if (resultCode != RESULT_OK) {
            throw TagoApiException(resultCode.ifBlank { "HTTP${response.status}" }, resultMsg)
        }
        val body = envelope.path("body")
        if (body.isMissingNode || body.isNull) return EMPTY_PAGE
        return TagoPage(
            totalCount = body.path("totalCount").asInt(0),
            pageNo = body.path("pageNo").asInt(1),
            numOfRows = body.path("numOfRows").asInt(0),
            // 데이터가 없으면 items가 통째로 빠지거나 빈 문자열로 오기도 한다 — 둘 다 빈 목록으로 본다.
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
        private val SOURCE = ExternalSource.TAGO
        const val PAGE_SIZE = 1000
        const val RESULT_OK = "00"
        private val EMPTY_PAGE = TagoPage(0, 1, 0, emptyList())
    }
}
