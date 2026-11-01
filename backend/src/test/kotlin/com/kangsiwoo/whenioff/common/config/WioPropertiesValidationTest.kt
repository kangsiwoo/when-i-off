package com.kangsiwoo.whenioff.common.config

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** WIO_API_TOKEN이 비거나 공백뿐이면 인증이 꺼지므로 기동 단계에서 실패해야 한다. */
class WioPropertiesValidationTest {
    @Configuration
    @EnableConfigurationProperties(WioProperties::class)
    class Config

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
            .withUserConfiguration(Config::class.java)

    @Test
    fun `empty api token fails startup`() {
        runner.withPropertyValues("wio.api-token=").run { ctx -> assertFailsOnApiToken(ctx.startupFailure) }
    }

    @Test
    fun `blank api token fails startup`() {
        runner.withPropertyValues("wio.api-token=   ").run { ctx -> assertFailsOnApiToken(ctx.startupFailure) }
    }

    @Test
    fun `non-blank api token starts`() {
        runner.withPropertyValues("wio.api-token=secret").run { ctx ->
            assertEquals(null, ctx.startupFailure)
            assertEquals("secret", ctx.getBean(WioProperties::class.java).apiToken)
        }
    }

    private fun assertFailsOnApiToken(failure: Throwable?) {
        assertNotNull(failure)
        val validation =
            generateSequence(failure) { it.cause }.filterIsInstance<BindValidationException>().firstOrNull()
        assertNotNull(validation, "expected a BindValidationException, got $failure")
        assertTrue(validation.validationErrors.allErrors.any { it.toString().contains("apiToken") })
    }
}
