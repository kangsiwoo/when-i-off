package com.kangsiwoo.whenioff.external.tago

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
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
    private val callCounter: TagoCallCounter,
    private val maxRetries: Int,
    private val retryBackoff: Duration,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
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
        return parseEnvelope(executeWithRetry(op, uri))
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

    private fun executeWithRetry(
        op: String,
        uri: URI,
    ): RawResponse {
        var attempt = 0
        while (true) {
            val outcome =
                try {
                    execute(op, uri)
                } catch (e: ResourceAccessException) {
                    RawResponse(0, e.message.orEmpty())
                }
            val retryable = outcome.status == 0 || outcome.status == 429 || outcome.status >= 500
            if (!retryable) return outcome
            if (attempt >= maxRetries) throw TagoGatewayException(outcome.status, outcome.body)
            attempt++
            val backoff = retryBackoff.multipliedBy(attempt.toLong())
            log.warn {
                "TAGO $op returned HTTP ${outcome.status}; retry $attempt/$maxRetries after ${backoff.toMillis()}ms"
            }
            sleeper(backoff)
        }
    }

    private fun execute(
        op: String,
        uri: URI,
    ): RawResponse {
        callCounter.record(op)
        return restClient
            .get()
            .uri(uri)
            .accept(MediaType.APPLICATION_JSON)
            .exchange { _, response ->
                RawResponse(
                    status = response.statusCode.value(),
                    body = response.body.readAllBytes().toString(StandardCharsets.UTF_8),
                )
            } ?: RawResponse(0, "no response")
    }

    private fun parseEnvelope(response: RawResponse): TagoPage {
        val root =
            try {
                objectMapper.readTree(response.body)
            } catch (e: JsonProcessingException) {
                throw TagoGatewayException(response.status, response.body)
            }
        if (root == null || !root.isObject) throw TagoGatewayException(response.status, response.body)
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
    )

    companion object {
        const val PAGE_SIZE = 1000
        const val RESULT_OK = "00"
        private val EMPTY_PAGE = TagoPage(0, 1, 0, emptyList())
    }
}
