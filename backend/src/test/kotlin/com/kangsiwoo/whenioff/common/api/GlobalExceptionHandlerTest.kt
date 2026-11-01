package com.kangsiwoo.whenioff.common.api

import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException
import kotlin.test.assertFalse

class GlobalExceptionHandlerTest {
    @RestController
    class ThrowingController {
        @GetMapping("/bad-request")
        fun badRequest(): Nothing = throw BadRequestException("routeId must be positive")

        @GetMapping("/illegal-argument")
        fun illegalArgument(): Nothing = throw IllegalArgumentException("internal invariant broken: secret_column")

        @GetMapping("/check-violation")
        fun checkViolation(): Nothing = throw integrity("23514", "new row violates check constraint \"leg_seq_check\"")

        @GetMapping("/unique-violation")
        fun uniqueViolation(): Nothing =
            throw integrity("23505", "duplicate key value violates unique constraint \"uq_trip\"")

        private fun integrity(
            state: String,
            message: String,
        ) = DataIntegrityViolationException("could not execute statement", SQLException(message, state))
    }

    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(ThrowingController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `bad request exception is a 400 with its message`() {
        mockMvc.get("/bad-request").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("routeId must be positive") }
        }
    }

    @Test
    fun `illegal argument is a server error, not a 400 that leaks its message`() {
        val body =
            mockMvc
                .get("/illegal-argument")
                .andExpect {
                    status { isInternalServerError() }
                    jsonPath("$.detail") { value("unexpected error") }
                }.andReturn()
                .response.contentAsString
        assertFalse(body.contains("secret_column"))
    }

    @Test
    fun `check violation is a 400 without the database message`() {
        val body =
            mockMvc
                .get("/check-violation")
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.detail") { value("request violates a data constraint") }
                }.andReturn()
                .response.contentAsString
        assertFalse(body.contains("leg_seq_check"))
    }

    @Test
    fun `other integrity violations are a 409 without the database message`() {
        val body =
            mockMvc
                .get("/unique-violation")
                .andExpect {
                    status { isConflict() }
                    jsonPath("$.detail") { value("request conflicts with existing data") }
                }.andReturn()
                .response.contentAsString
        assertFalse(body.contains("uq_trip"))
    }
}
