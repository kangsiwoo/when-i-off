package com.kangsiwoo.whenioff.external.metrics

import org.springframework.web.client.ResourceAccessException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException

/** 외부 API 소스. [tag]가 메트릭 태그·로그 필드·조회 API에 쓰이는 값이다. */
enum class ExternalSource(
    val tag: String,
) {
    TAGO("tago"),
    KLID("klid"),
}

/**
 * HTTP 시도 한 번의 결과 (#76).
 *
 *  - `success`: 정상 결과 코드(KLID `K3` NODATA 포함 — 오류가 아니다)
 *  - `http_error`: 2xx가 아닌 응답인데 결과 코드를 읽을 수 없다(평문 403, 5xx, 429 …)
 *  - `api_error`: 결과 코드를 읽었는데 비정상(`22` 한도 초과, `30` 미등록 키 …). 상태 코드와 상관없다 —
 *    게이트웨이는 미등록 키를 HTTP 403 + 결과 코드 JSON으로 준다. 2xx인데 봉투를 읽을 수 없는 응답도 여기
 *  - `timeout`: 연결/읽기 타임아웃
 *  - `io_error`: 그 밖의 I/O 실패(연결 거부, DNS …)
 */
enum class CallOutcome(
    val tag: String,
) {
    SUCCESS("success"),
    HTTP_ERROR("http_error"),
    API_ERROR("api_error"),
    TIMEOUT("timeout"),
    IO_ERROR("io_error"),
    ;

    val failure: Boolean get() = this != SUCCESS

    companion object {
        /** 응답을 받지 못한 시도. 원인 사슬에 타임아웃이 있으면 `timeout`. */
        fun of(e: ResourceAccessException): CallOutcome =
            if (causes(e).any { it is SocketTimeoutException || it is HttpTimeoutException }) TIMEOUT else IO_ERROR

        /**
         * 응답을 받지 못한 시도의 설명. [ResourceAccessException.message]에는 요청 URL이 — 즉 `serviceKey`가 —
         * 들어 있으므로 쓰지 않고 가장 안쪽 원인의 타입과 메시지만 남긴다.
         */
        fun describe(e: ResourceAccessException): String {
            val root = causes(e).last()
            if (root === e) return "I/O error"
            return listOfNotNull(root.javaClass.simpleName, root.message).joinToString(": ")
        }

        private fun causes(e: Throwable): List<Throwable> =
            generateSequence(e) {
                it.cause.takeIf { c -> c !== it }
            }.take(10).toList()
    }
}
