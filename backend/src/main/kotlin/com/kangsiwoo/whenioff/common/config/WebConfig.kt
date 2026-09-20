package com.kangsiwoo.whenioff.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.auth.ApiTokenFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration
class WebConfig {
    @Bean
    fun apiTokenFilterRegistration(
        properties: WioProperties,
        objectMapper: ObjectMapper,
    ): FilterRegistrationBean<ApiTokenFilter> =
        FilterRegistrationBean(ApiTokenFilter(properties.apiToken, objectMapper)).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/api/*")
        }
}
