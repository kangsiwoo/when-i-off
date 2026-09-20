package com.kangsiwoo.whenioff.external.klid

import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

class KlidCallCounter(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.system(KlidTime.KST),
) {
    @Volatile
    private var day: LocalDate = LocalDate.now(clock)
    private val count = AtomicLong()

    fun record(op: String) {
        meterRegistry.counter("wio.klid.calls", "op", op).increment()
        rollDay()
        count.incrementAndGet()
    }

    fun todayCount(): Long {
        rollDay()
        return count.get()
    }

    @Synchronized
    private fun rollDay() {
        val today = LocalDate.now(clock)
        if (today != day) {
            day = today
            count.set(0)
        }
    }
}
