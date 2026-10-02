package com.kangsiwoo.whenioff.external.metrics

import com.kangsiwoo.whenioff.support.MutableClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 오늘(KST)·최근 1시간 집계와 백분위 (#76). */
class ExternalCallStoreTest {
    // 2026-10-01 23:50 KST
    private val clock = MutableClock(Instant.parse("2026-10-01T14:50:00Z"))
    private val store = ExternalCallStore(clock)

    private fun record(
        op: String = OP,
        ms: Long = 100,
        outcome: CallOutcome = CallOutcome.SUCCESS,
        source: ExternalSource = ExternalSource.TAGO,
    ) = store.record(source, op, Duration.ofMillis(ms), outcome)

    private fun stats(op: String = OP) = store.snapshot().single { it.source == ExternalSource.TAGO && it.op == op }

    @Test
    fun `today resets at KST midnight while the last hour window carries across it`() {
        repeat(3) { record() }
        record(outcome = CallOutcome.API_ERROR)
        assertEquals(4, stats().today.calls)
        assertEquals(4, store.todayCalls(ExternalSource.TAGO))

        // 23:59:59 KST — 아직 같은 날
        clock.now = Instant.parse("2026-10-01T14:59:59Z")
        assertEquals(4, store.todayCalls(ExternalSource.TAGO))

        // 00:00 KST(= 15:00 UTC). UTC 날짜는 그대로지만 KST 날짜가 바뀌었다
        clock.now = Instant.parse("2026-10-01T15:00:00Z")
        assertEquals(0, store.todayCalls(ExternalSource.TAGO))
        val afterMidnight = stats()
        assertEquals(0, afterMidnight.today.calls)
        assertNull(afterMidnight.today.failureRate)
        assertNull(afterMidnight.today.p50Ms)
        // 최근 1시간은 날짜와 상관없다
        assertEquals(4, afterMidnight.lastHour.calls)
        assertEquals(1, afterMidnight.lastHour.failures)

        record(outcome = CallOutcome.TIMEOUT)
        assertEquals(1, stats().today.calls)
        assertEquals(1, stats().today.failures)
        assertEquals(mapOf(CallOutcome.TIMEOUT to 1L), stats().today.outcomes)
        assertEquals(5, stats().lastHour.calls)
    }

    @Test
    fun `the last hour keeps 60 minute buckets including the current one`() {
        record() // 14:50
        clock.advance(Duration.ofMinutes(30))
        record(outcome = CallOutcome.HTTP_ERROR) // 15:20
        assertEquals(2, stats().lastHour.calls)

        // 15:49:59 — 14:50 분 칸이 아직 60개 안에 든다
        clock.now = Instant.parse("2026-10-01T15:49:59Z")
        assertEquals(2, stats().lastHour.calls)

        // 15:50 — 14:50 칸이 빠진다
        clock.now = Instant.parse("2026-10-01T15:50:00Z")
        val s = stats()
        assertEquals(1, s.lastHour.calls)
        assertEquals(1.0, s.lastHour.failureRate)

        clock.advance(Duration.ofHours(2))
        assertEquals(0, stats().lastHour.calls)
    }

    @Test
    fun `failure rate and percentiles per op and source`() {
        // 1..100ms 하나씩: p50 = 50, p95 = 95 (칸 경계 오차 9% 안)
        for (ms in 1L..100L) {
            record(
                ms = ms,
                outcome =
                    if (ms % 10 ==
                        0L
                    ) {
                        CallOutcome.API_ERROR
                    } else {
                        CallOutcome.SUCCESS
                    },
            )
        }
        record(op = "other", ms = 7)
        record(source = ExternalSource.KLID, op = OP, ms = 3)

        val s = stats()
        assertEquals(100, s.today.calls)
        assertEquals(10, s.today.failures)
        assertEquals(0.1, s.today.failureRate)
        assertTrue(s.today.p50Ms!! in 50L..55L, "p50 ${s.today.p50Ms}")
        assertTrue(s.today.p95Ms!! in 95L..100L, "p95 ${s.today.p95Ms}")
        assertEquals(s.today, s.lastHour)

        val other = stats("other")
        assertEquals(7, other.today.p50Ms)
        assertEquals(7, other.today.p95Ms)
        assertEquals(101, store.todayCalls(ExternalSource.TAGO))
        assertEquals(1, store.todayCalls(ExternalSource.KLID))
    }

    @Test
    fun `histogram percentiles stay within the observed range`() {
        val h = LatencyHistogram()
        assertNull(h.percentileMs(0.5))
        h.record(0.4)
        assertEquals(0, h.percentileMs(0.5))
        h.record(200_000.0) // 최대 칸 밖
        assertEquals(200_000, h.percentileMs(0.95))
        val merged = LatencyHistogram().apply { add(h) }
        assertEquals(2, merged.count)
        assertEquals(200_000, merged.percentileMs(1.0))
    }

    @Test
    fun `metrics records a Timer sample per attempt tagged with source op outcome`() {
        val registry = SimpleMeterRegistry()
        val metrics = ExternalCallMetrics(registry, store)
        metrics.record(ExternalSource.KLID, "tl_drct_info", Duration.ofMillis(120), CallOutcome.SUCCESS)
        metrics.record(ExternalSource.KLID, "tl_drct_info", Duration.ofMillis(80), CallOutcome.SUCCESS)
        metrics.record(ExternalSource.KLID, "tl_drct_info", Duration.ofMillis(20), CallOutcome.IO_ERROR)

        val ok =
            registry
                .get(ExternalCallMetrics.METRIC)
                .tags("source", "klid", "op", "tl_drct_info", "outcome", "success")
                .timer()
        assertEquals(2, ok.count())
        assertEquals(200.0, ok.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        assertEquals(
            1,
            registry
                .get(ExternalCallMetrics.METRIC)
                .tag("outcome", "io_error")
                .timer()
                .count(),
        )
        assertEquals(3, metrics.todayCount(ExternalSource.KLID))
    }

    companion object {
        private const val OP = "getRouteNoList"
    }
}
