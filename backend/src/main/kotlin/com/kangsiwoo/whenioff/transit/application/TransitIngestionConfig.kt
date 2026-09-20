package com.kangsiwoo.whenioff.transit.application

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class TransitIngestionConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
