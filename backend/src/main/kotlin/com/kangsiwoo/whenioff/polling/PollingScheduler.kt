package com.kangsiwoo.whenioff.polling

import com.kangsiwoo.whenioff.common.config.WioProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock

private val log = KotlinLogging.logger {}

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "wio.polling", name = ["enabled"], havingValue = "true")
class PollingConfig {
    @Bean
    fun windowChecker(properties: WioProperties): WindowChecker = WindowChecker(properties.polling.windows)

    @Bean
    fun pollingScheduler(
        cycleService: PollingCycleService,
        windowChecker: WindowChecker,
        clock: Clock,
    ): PollingScheduler = PollingScheduler(cycleService, windowChecker, clock)
}

class PollingScheduler(
    private val cycleService: PollingCycleService,
    private val windowChecker: WindowChecker,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${wio.polling.interval-ms}")
    fun poll() {
        if (!windowChecker.isWithin(clock.instant())) {
            log.debug { "outside polling window; skipping" }
            return
        }
        try {
            cycleService.runCycle()
        } catch (e: RuntimeException) {
            log.error(e) { "polling cycle failed" }
        }
    }
}
