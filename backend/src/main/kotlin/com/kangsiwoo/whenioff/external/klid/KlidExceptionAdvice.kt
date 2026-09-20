package com.kangsiwoo.whenioff.external.klid

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val log = KotlinLogging.logger {}

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class KlidExceptionAdvice {
    @ExceptionHandler(KlidNotConfiguredException::class)
    fun handleNotConfigured(e: KlidNotConfiguredException): ProblemDetail {
        log.error { e.message }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", e.message)
    }

    @ExceptionHandler(KlidException::class)
    fun handleKlid(e: KlidException): ProblemDetail {
        log.warn(e) { "KLID upstream failure" }
        return problem(HttpStatus.BAD_GATEWAY, "Bad Gateway", e.message).apply {
            when (e) {
                is KlidApiException -> setProperty("klidResultCode", e.resultCode)
                is KlidGatewayException -> setProperty("klidResultCode", "HTTP${e.status}")
                is KlidNotConfiguredException -> Unit
            }
        }
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: title).apply { this.title = title }
}
