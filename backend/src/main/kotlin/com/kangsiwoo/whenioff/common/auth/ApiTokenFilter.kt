package com.kangsiwoo.whenioff.common.auth

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

class ApiTokenFilter(
    private val expectedToken: String,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val presented = request.getHeader(HEADER)
        if (presented != null && constantTimeEquals(presented, expectedToken)) {
            filterChain.doFilter(request, response)
            return
        }
        val problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "missing or invalid $HEADER header").apply {
                title = "Unauthorized"
            }
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(problem))
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val HEADER = "X-Api-Token"
    }
}
