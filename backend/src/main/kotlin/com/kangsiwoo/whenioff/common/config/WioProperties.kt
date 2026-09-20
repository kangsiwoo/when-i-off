package com.kangsiwoo.whenioff.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "wio")
data class WioProperties(
    val apiToken: String,
    val klid: Klid = Klid(),
    val polling: Polling = Polling(),
) {
    data class Klid(
        val bus: Endpoint = Endpoint("https://apis.data.go.kr/B551982/rte", ""),
        val signal: Endpoint = Endpoint("https://apis.data.go.kr/B551982/rti", ""),
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val readTimeout: Duration = Duration.ofSeconds(20),
        val maxRetries: Int = 2,
        val retryBackoff: Duration = Duration.ofMillis(500),
    )

    data class Endpoint(
        val baseUrl: String,
        val serviceKey: String,
    )

    data class Polling(
        val enabled: Boolean = false,
        val intervalMs: Long = 60_000,
        val windows: List<String> = listOf("06:30-09:30", "17:30-20:30"),
    )
}
