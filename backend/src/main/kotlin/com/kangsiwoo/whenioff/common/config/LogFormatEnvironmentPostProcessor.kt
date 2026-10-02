package com.kangsiwoo.whenioff.common.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * `WIO_LOG_FORMAT`(text | json)을 Spring Boot 구조화 로그 설정으로 옮긴다 (#76).
 *
 * `json`이면 콘솔 로그를 ECS JSON(`logging.structured.format.console=ecs`, Boot 3.4+ 기본 지원)으로 바꾼다.
 * 비었거나 `text`면 아무것도 하지 않는다 — 지금처럼 텍스트. 그 밖의 값은 오타가 조용히 텍스트로 바뀌지 않게
 * 시작을 멈춘다. `logging.structured.format.console`을 직접 준 경우는 그 값을 따른다.
 * 로깅 시스템은 이 후처리기 다음(`LoggingApplicationListener`)에 초기화되므로 시작 로그부터 적용된다.
 */
class LogFormatEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val format =
            environment
                .getProperty(ENV)
                ?.trim()
                ?.lowercase()
                .orEmpty()
        when (format) {
            "", "text" -> return
            "json" -> Unit
            else -> throw IllegalArgumentException("$ENV must be 'text' or 'json': '$format'")
        }
        if (environment.containsProperty(STRUCTURED_CONSOLE)) return
        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, mapOf(STRUCTURED_CONSOLE to "ecs")))
    }

    companion object {
        const val ENV = "WIO_LOG_FORMAT"
        const val STRUCTURED_CONSOLE = "logging.structured.format.console"
        private const val SOURCE_NAME = "wioLogFormat"
    }
}
