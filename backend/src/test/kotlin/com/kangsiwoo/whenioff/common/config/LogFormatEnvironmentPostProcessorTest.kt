package com.kangsiwoo.whenioff.common.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `WIO_LOG_FORMAT` → Spring Boot 구조화 로그 (#76). */
class LogFormatEnvironmentPostProcessorTest {
    private val processor = LogFormatEnvironmentPostProcessor()

    private fun apply(env: MockEnvironment): String? {
        processor.postProcessEnvironment(env, SpringApplication())
        return env.getProperty(LogFormatEnvironmentPostProcessor.STRUCTURED_CONSOLE)
    }

    @Test
    fun `json switches the console to ECS`() {
        assertEquals("ecs", apply(MockEnvironment().withProperty("WIO_LOG_FORMAT", "json")))
        assertEquals("ecs", apply(MockEnvironment().withProperty("WIO_LOG_FORMAT", " JSON ")))
    }

    @Test
    fun `unset or text keeps the plain text console`() {
        assertNull(apply(MockEnvironment()))
        assertNull(apply(MockEnvironment().withProperty("WIO_LOG_FORMAT", "text")))
    }

    @Test
    fun `an explicit structured format wins`() {
        val env =
            MockEnvironment()
                .withProperty("WIO_LOG_FORMAT", "json")
                .withProperty("logging.structured.format.console", "logstash")
        assertEquals("logstash", apply(env))
    }

    @Test
    fun `an unknown value fails fast`() {
        assertThrows<IllegalArgumentException> { apply(MockEnvironment().withProperty("WIO_LOG_FORMAT", "jsno")) }
    }
}
