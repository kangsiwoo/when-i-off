package com.kangsiwoo.whenioff.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "wio")
data class WioProperties(
    val apiToken: String,
    val tago: Tago = Tago(),
    val klid: Klid = Klid(),
    val polling: Polling = Polling(),
    val retention: Retention = Retention(),
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
        /** 오퍼레이션(포털 "상세기능")마다의 일 호출 한도. 개발계정 1,000회 (#76, 운영 조회의 사용률 분모). */
        val dailyLimit: Long = 1_000,
        /** op 이름 → 한도. 운영계정 전환 등으로 일부 op만 다를 때. */
        val dailyLimitOverrides: Map<String, Long> = emptyMap(),
    ) {
        fun dailyLimitOf(op: String): Long = dailyLimitOverrides[op] ?: dailyLimit

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
        /** 오퍼레이션마다의 일 호출 한도. 개발계정 5,000회 수준 (ARCHITECTURE "호출 한도"). */
        val dailyLimit: Long = 5_000,
        val dailyLimitOverrides: Map<String, Long> = emptyMap(),
    ) {
        fun dailyLimitOf(op: String): Long = dailyLimitOverrides[op] ?: dailyLimit
    }

    data class Endpoint(
        val baseUrl: String,
        val serviceKey: String,
    )

    data class Polling(
        val enabled: Boolean = false,
        val intervalMs: Long = 60_000,
        val windows: List<String> = listOf("06:30-09:30", "17:30-20:30"),
    )

    /**
     * 오래된 원본 데이터 정리 (#74, DATA_MODEL "보관 정책"). 기간은 일(day) 단위이고 기준 시각은 실행 시점이다.
     */
    data class Retention(
        val enabled: Boolean = false,
        /** Spring 6필드 cron, KST. analytics 새벽 배치(03:00) 뒤에 돈다. */
        val cron: String = "0 30 4 * * *",
        /** true면 지울 행 수만 세고 지우지 않는다. */
        val dryRun: Boolean = false,
        /** 도보 구간이 파생된 trip의 점, trip 없는 상시 수집분. */
        val gpsDays: Long = 90,
        /** 도보 구간이 하나도 파생되지 않은 trip의 점 — 나중에 파생할 수 있게 더 오래 두는 상한. */
        val gpsUnderivedDays: Long = 365,
        val observationDays: Long = 365,
        val signalStateDays: Long = 30,
        /** 한 DELETE 문이 지우는 최대 행 수. 문마다 커밋해 잠금을 짧게 유지한다. */
        val batchSize: Int = 5_000,
    ) {
        init {
            require(gpsDays > 0 && observationDays > 0 && signalStateDays > 0) { "retention days must be positive" }
            require(gpsUnderivedDays >= gpsDays) { "gps-underived-days must be >= gps-days" }
            require(batchSize > 0) { "retention batch-size must be positive" }
        }
    }
}
