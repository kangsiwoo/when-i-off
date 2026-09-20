package com.kangsiwoo.whenioff.external.tago

import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

// KLID와 같은 구조지만 메트릭 이름(wio.tago.calls)과 일 한도가 서로 다른 서비스라 카운터를 따로 둔다.
class TagoCallCounter(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.system(KST),
) {
    @Volatile
    private var day: LocalDate = LocalDate.now(clock)
    private val count = AtomicLong()

    fun record(op: String) {
        meterRegistry.counter("wio.tago.calls", "op", op).increment()
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

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
