package com.kangsiwoo.whenioff.signal.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TrafficSignalCycleRepository : JpaRepository<TrafficSignalCycle, Long> {
    fun findByTrafficSignal(trafficSignal: TrafficSignal): List<TrafficSignalCycle>

    fun findByTrafficSignalAndSource(
        trafficSignal: TrafficSignal,
        source: SignalDataSource,
    ): List<TrafficSignalCycle>
}
