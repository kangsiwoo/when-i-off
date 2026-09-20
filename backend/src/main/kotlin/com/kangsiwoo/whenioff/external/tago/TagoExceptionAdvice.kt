package com.kangsiwoo.whenioff.external.tago

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
class TagoExceptionAdvice {
    @ExceptionHandler(TagoNotConfiguredException::class)
    fun handleNotConfigured(e: TagoNotConfiguredException): ProblemDetail {
        log.error { e.message }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", e.message)
    }

    @ExceptionHandler(TagoException::class)
    fun handleTago(e: TagoException): ProblemDetail {
        log.warn(e) { "TAGO upstream failure" }
        return problem(HttpStatus.BAD_GATEWAY, "Bad Gateway", e.message).apply {
            when (e) {
                is TagoApiException -> setProperty("tagoResultCode", e.resultCode)
                is TagoGatewayException -> setProperty("tagoResultCode", "HTTP${e.status}")
                is TagoNotConfiguredException -> Unit
            }
        }
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: title).apply { this.title = title }
}
