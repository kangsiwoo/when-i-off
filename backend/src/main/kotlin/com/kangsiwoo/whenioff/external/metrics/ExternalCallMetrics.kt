package com.kangsiwoo.whenioff.external.metrics

import io.github.oshai.kotlinlogging.KLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * 외부 API HTTP 시도 하나하나를 기록한다 (#76).
 *
 * **시도 단위**다: 재시도하면 시도마다 한 번씩 기록되고(`attempt` 태그는 두지 않는다 — 재시도도 공공데이터포털 일 한도를
 * 똑같이 쓰므로 한도 사용률과 같은 기준으로 센다), 키가 없어 호출하지 않은 것은 기록하지 않는다.
 * 같은 기록이 두 곳으로 간다:
 *  - Micrometer Timer `wio.external.calls{source, op, outcome}` — 프로세스 누적(지금은 Actuator로 노출하지 않는다)
 *  - [ExternalCallStore] — 오늘(KST)·최근 1시간 집계, 조회 API와 폴링 결과의 오늘 호출 수
 * 예전의 `wio.tago.calls`/`wio.klid.calls` 카운터와 `TagoCallCounter`/`KlidCallCounter`는 이것으로 대체했다.
 */
class ExternalCallMetrics(
    private val meterRegistry: MeterRegistry,
    val store: ExternalCallStore,
) {
    fun record(
        source: ExternalSource,
        op: String,
        duration: Duration,
        outcome: CallOutcome,
    ) {
        Timer
            .builder(METRIC)
            .description("External API HTTP attempts (TAGO/KLID), one sample per attempt including retries")
            .tags("source", source.tag, "op", op, "outcome", outcome.tag)
            .register(meterRegistry)
            .record(duration)
        store.record(source, op, duration, outcome)
    }

    fun todayCount(source: ExternalSource): Long = store.todayCalls(source)

    companion object {
        const val METRIC = "wio.external.calls"
    }
}

/**
 * 외부 호출 로그에 `source`/`op`/`outcome`을 MDC로 싣는다. JSON 로그(`WIO_LOG_FORMAT=json`)에서는 필드가 되고,
 * 텍스트 로그에서도 보이도록 메시지에도 `key=value`로 쓴다.
 */
object ExternalCallLog {
    fun warn(
        log: KLogger,
        source: ExternalSource,
        op: String,
        outcome: CallOutcome,
        message: String,
    ) {
        MDC.putCloseable("source", source.tag).use {
            MDC.putCloseable("op", op).use {
                MDC.putCloseable("outcome", outcome.tag).use {
                    log.warn { "external call failed: source=${source.tag} op=$op outcome=${outcome.tag} $message" }
                }
            }
        }
    }
}

@Configuration
class ExternalMetricsConfig {
    @Bean
    fun externalCallStore(clock: Clock): ExternalCallStore = ExternalCallStore(clock)

    @Bean
    fun externalCallMetrics(
        meterRegistry: MeterRegistry,
        store: ExternalCallStore,
    ): ExternalCallMetrics = ExternalCallMetrics(meterRegistry, store)
}
