package com.kangsiwoo.whenioff.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "wio")
data class WioProperties(
    val apiToken: String,
    val tago: Tago = Tago(),
    val klid: Klid = Klid(),
    val polling: Polling = Polling(),
) {
    /**
     * TAGO(버스). 버스노선정보/버스도착정보는 서로 다른 서비스 URL이지만 포털 계정 서비스 키 하나를
     * 같이 쓰므로 키는 하나만 두고 URL만 나눈다.
     */
    data class Tago(
        val serviceKey: String = "",
        val routeInfoBaseUrl: String = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService",
        val arrivalInfoBaseUrl: String = "https://apis.data.go.kr/1613000/ArvlInfoInqireService",
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val readTimeout: Duration = Duration.ofSeconds(20),
        val maxRetries: Int = 2,
        val retryBackoff: Duration = Duration.ofMillis(500),
    ) {
        val routeInfo: Endpoint get() = Endpoint(routeInfoBaseUrl, serviceKey)
        val arrivalInfo: Endpoint get() = Endpoint(arrivalInfoBaseUrl, serviceKey)
    }

    data class Klid(
        val signal: Endpoint = Endpoint("https://apis.data.go.kr/B551982/rti", ""),
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val readTimeout: Duration = Duration.ofSeconds(20),
        val maxRetries: Int = 2,
        val retryBackoff: Duration = Duration.ofMillis(500),
        /**
         * `tl_drct_info`가 K3(NODATA)를 준 `stdgCd`를 다시 찔러보기까지 기다리는 시간 (#31).
         * 6시간이면 출퇴근 창(각 3시간) 하나를 통째로 덮어 그 창의 호출을 거의 다 아끼면서도,
         * 하루에 네 번은 다시 확인하므로 커버리지가 늘어난 날 안에 사람 개입 없이 복구된다.
         * `0`이면 표시가 즉시 만료되어 건너뛰기가 사실상 꺼진다.
         */
        val noDataTtl: Duration = Duration.ofHours(6),
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
