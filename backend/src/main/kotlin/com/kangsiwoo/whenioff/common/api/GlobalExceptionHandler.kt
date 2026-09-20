package com.kangsiwoo.whenioff.common.api

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

private val log = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(e: NotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, "Not Found", e.message)

    @ExceptionHandler(BadRequestException::class, IllegalArgumentException::class)
    fun handleBadRequest(e: RuntimeException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "Bad Request", e.message)

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Bad Request", "validation failed").apply {
            setProperty("errors", e.constraintViolations.map { "${it.propertyPath}: ${it.message}" })
        }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(e: DataIntegrityViolationException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "Conflict",
            e.mostSpecificCause.message
                ?.lineSequence()
                ?.firstOrNull(),
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ProblemDetail {
        log.error(e) { "unhandled exception" }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "unexpected error")
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val body =
            problem(HttpStatus.BAD_REQUEST, "Bad Request", "validation failed").apply {
                setProperty(
                    "errors",
                    ex.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" } +
                        ex.bindingResult.globalErrors.map { it.defaultMessage ?: it.code.orEmpty() },
                )
            }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: title).apply { this.title = title }
}
