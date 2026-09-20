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
    @ExceptionHandler(KlidException::class)
    fun handleKlid(e: KlidException): ProblemDetail {
        log.warn(e) { "KLID upstream failure" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.message ?: "KLID upstream failure").apply {
            title = "Bad Gateway"
        }
    }
}
