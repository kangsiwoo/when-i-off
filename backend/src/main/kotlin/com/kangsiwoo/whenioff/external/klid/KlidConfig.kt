package com.kangsiwoo.whenioff.external.klid

import com.fasterxml.jackson.databind.ObjectMapper
import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.klid.signal.KlidSignalApi
import com.kangsiwoo.whenioff.external.metrics.ExternalCallMetrics
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration
class KlidConfig {
    @Bean
    fun klidNoDataRegistry(
        properties: WioProperties,
        clock: Clock,
    ): KlidNoDataRegistry = KlidNoDataRegistry(properties.klid.noDataTtl, clock)

    @Bean
    fun klidHttpClient(
        properties: WioProperties,
        objectMapper: ObjectMapper,
        metrics: ExternalCallMetrics,
    ): KlidHttpClient {
        val klid = properties.klid
        val settings =
            ClientHttpRequestFactorySettings
                .defaults()
                .withConnectTimeout(klid.connectTimeout)
                .withReadTimeout(klid.readTimeout)
        val restClient =
            RestClient
                .builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build()
        return KlidHttpClient(restClient, objectMapper, metrics, klid.maxRetries, klid.retryBackoff)
    }

    @Bean
    fun klidSignalApi(
        properties: WioProperties,
        client: KlidHttpClient,
    ): KlidSignalApi = KlidSignalApi(client, properties.klid.signal)
}
