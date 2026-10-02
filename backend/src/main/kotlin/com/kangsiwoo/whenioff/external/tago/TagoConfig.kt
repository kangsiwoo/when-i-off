package com.kangsiwoo.whenioff.external.tago

import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.metrics.ExternalCallMetrics
import com.kangsiwoo.whenioff.external.tago.bus.TagoArrivalApi
import com.kangsiwoo.whenioff.external.tago.bus.TagoBusRouteApi
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class TagoConfig {
    @Bean
    fun tagoHttpClient(
        properties: WioProperties,
        objectMapper: ObjectMapper,
        metrics: ExternalCallMetrics,
    ): TagoHttpClient {
        val tago = properties.tago
        val settings =
            ClientHttpRequestFactorySettings
                .defaults()
                .withConnectTimeout(tago.connectTimeout)
                .withReadTimeout(tago.readTimeout)
        val restClient =
            RestClient
                .builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build()
        return TagoHttpClient(restClient, objectMapper, metrics, tago.maxRetries, tago.retryBackoff)
    }

    @Bean
    fun tagoBusRouteApi(
        properties: WioProperties,
        client: TagoHttpClient,
    ): TagoBusRouteApi = TagoBusRouteApi(client, properties.tago.routeInfo)

    @Bean
    fun tagoArrivalApi(
        properties: WioProperties,
        client: TagoHttpClient,
    ): TagoArrivalApi = TagoArrivalApi(client, properties.tago.arrivalInfo)
}
