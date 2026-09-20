package com.kangsiwoo.whenioff.external.klid

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

data class KlidPage(
    val totalCount: Int,
    val pageNo: Int,
    val numOfRows: Int,
    val items: List<Map<String, String>>,
)

class KlidHttpClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val callCounter: KlidCallCounter,
    private val maxRetries: Int,
    private val retryBackoff: Duration,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    fun fetchAll(
        endpoint: WioProperties.Endpoint,
        op: String,
        stdgCd: String,
    ): List<Map<String, String>> {
        val all = mutableListOf<Map<String, String>>()
        var pageNo = 1
        while (true) {
            val page = fetchPage(endpoint, op, stdgCd, pageNo, PAGE_SIZE)
            all += page.items
            if (page.items.isEmpty() || page.items.size < PAGE_SIZE || all.size >= page.totalCount) break
            pageNo++
        }
        return all
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
        return parseEnvelope(executeWithRetry(op, uri))
    }

    // data.go.kr keys contain '+', '/', '=': Spring's UriBuilder leaves '+' and '/' raw, which the gateway
    // then decodes as a space, so every value is percent-encoded here exactly once with URLEncoder.
    private fun buildUri(
        endpoint: WioProperties.Endpoint,
        op: String,
        stdgCd: String,
        pageNo: Int,
        numOfRows: Int,
    ): URI {
        val query =
            listOf(
                "serviceKey" to endpoint.serviceKey,
                "pageNo" to pageNo.toString(),
                "numOfRows" to numOfRows.toString(),
                "type" to "json",
                "stdgCd" to stdgCd,
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        return URI.create("${endpoint.baseUrl.trimEnd('/')}/$op?$query")
    }

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
            if (attempt >= maxRetries) throw KlidGatewayException(outcome.status, outcome.body)
            attempt++
            val backoff = retryBackoff.multipliedBy(attempt.toLong())
            log.warn {
                "KLID $op returned HTTP ${outcome.status}; retry $attempt/$maxRetries after ${backoff.toMillis()}ms"
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
            RESULT_NODATA -> return EMPTY_PAGE
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
    )

    companion object {
        const val PAGE_SIZE = 1000
        const val RESULT_OK = "K0"
        const val RESULT_NODATA = "K3"
        private val EMPTY_PAGE = KlidPage(0, 1, 0, emptyList())
    }
}
