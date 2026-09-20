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
import java.sql.SQLException

private val log = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(e: NotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, "Not Found", e.message)

    @ExceptionHandler(BadRequestException::class, IllegalArgumentException::class)
    fun handleBadRequest(e: RuntimeException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "Bad Request", e.message)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(e: ConflictException): ProblemDetail = problem(HttpStatus.CONFLICT, "Conflict", e.message)

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Bad Request", "validation failed").apply {
            setProperty("errors", e.constraintViolations.map { "${it.propertyPath}: ${it.message}" })
        }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(e: DataIntegrityViolationException): ProblemDetail {
        val cause = e.mostSpecificCause
        val detail = cause.message?.lineSequence()?.firstOrNull()
        // 23514(check_violation)은 요청 값이 도메인 제약을 어긴 것이므로 충돌(409)이 아니라 잘못된 요청(400)이다.
        return if ((cause as? SQLException)?.sqlState == "23514") {
            problem(HttpStatus.BAD_REQUEST, "Bad Request", detail)
        } else {
            problem(HttpStatus.CONFLICT, "Conflict", detail)
        }
    }

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
