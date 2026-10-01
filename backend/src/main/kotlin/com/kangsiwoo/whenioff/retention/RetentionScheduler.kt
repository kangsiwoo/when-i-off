package com.kangsiwoo.whenioff.retention

import com.kangsiwoo.whenioff.common.config.WioProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

private val log = KotlinLogging.logger {}

/** 폴링처럼 설정으로만 켠다 (`WIO_RETENTION_ENABLED=true`). 꺼져 있으면 스케줄러 빈 자체가 없다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "wio.retention", name = ["enabled"], havingValue = "true")
class RetentionConfig {
    @Bean
    fun retentionScheduler(
        service: RetentionService,
        properties: WioProperties,
    ): RetentionScheduler {
        val r = properties.retention
        log.info {
            "retention scheduled: cron '${r.cron}' (KST), dry-run ${r.dryRun}, gps ${r.gpsDays}d " +
                "(underived trips ${r.gpsUnderivedDays}d), observations ${r.observationDays}d, " +
                "signal states ${r.signalStateDays}d, batch ${r.batchSize}"
        }
        return RetentionScheduler(service)
    }
}

class RetentionScheduler(
    private val service: RetentionService,
) {
    @Scheduled(cron = "\${wio.retention.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    fun purge() {
        try {
            service.run()
        } catch (e: RuntimeException) {
            // 배치마다 커밋하므로 실패 전까지 지운 행은 남고, 다음 실행이 나머지를 이어서 지운다.
            log.error(e) { "retention run failed" }
        }
    }
}
