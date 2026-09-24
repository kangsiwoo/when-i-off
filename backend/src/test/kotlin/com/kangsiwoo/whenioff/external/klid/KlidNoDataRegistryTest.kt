package com.kangsiwoo.whenioff.external.klid

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlidNoDataRegistryTest {
    /** TTL 경과를 `Thread.sleep` 없이 결정적으로 확인하기 위한 수동 시계. */
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(amount: Duration) {
            now = now.plus(amount)
        }
    }

    private val clock = MutableClock(Instant.parse("2026-09-24T00:00:00Z"))
    private val registry = KlidNoDataRegistry(Duration.ofHours(6), clock)

    @Test
    fun `an unmarked stdgCd is never skipped`() {
        assertFalse(registry.isMarked(SEOUL))
    }

    @Test
    fun `a marked stdgCd stays skipped for the whole TTL`() {
        registry.mark(SEOUL)

        assertTrue(registry.isMarked(SEOUL))
        clock.advance(Duration.ofHours(5).plusMinutes(59))
        assertTrue(registry.isMarked(SEOUL))
        // 다른 지자체까지 같이 막히면 안 된다.
        assertFalse(registry.isMarked(ULSAN))
    }

    @Test
    fun `the mark expires at the TTL so the region is probed again`() {
        registry.mark(SEOUL)

        clock.advance(Duration.ofHours(6))

        assertFalse(registry.isMarked(SEOUL))
    }

    @Test
    fun `a region that is still NODATA after the TTL can be marked again`() {
        registry.mark(SEOUL)
        clock.advance(Duration.ofHours(6))
        assertFalse(registry.isMarked(SEOUL))

        registry.mark(SEOUL)

        assertTrue(registry.isMarked(SEOUL))
        clock.advance(Duration.ofHours(5))
        assertTrue(registry.isMarked(SEOUL))
        clock.advance(Duration.ofHours(1))
        assertFalse(registry.isMarked(SEOUL))
    }

    @Test
    fun `a zero TTL turns skipping off`() {
        val off = KlidNoDataRegistry(Duration.ZERO, clock)

        off.mark(SEOUL)

        assertFalse(off.isMarked(SEOUL))
    }

    @Test
    fun `clear drops every mark`() {
        registry.mark(SEOUL)
        registry.mark(ULSAN)

        registry.clear()

        assertFalse(registry.isMarked(SEOUL))
        assertFalse(registry.isMarked(ULSAN))
    }

    private companion object {
        const val SEOUL = "1100000000"
        const val ULSAN = "3100000000"
    }
}
