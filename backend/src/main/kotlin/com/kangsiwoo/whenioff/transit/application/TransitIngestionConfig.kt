package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.external.klid.bus.KlidBusApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@Configuration
class TransitIngestionConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun busPositionSnapshotCache(
        busApi: KlidBusApi,
        clock: Clock,
    ): BusPositionSnapshotCache = BusPositionSnapshotCache(busApi, clock, Duration.ofSeconds(30))
}
