package com.kangsiwoo.whenioff.polling

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowCheckerTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val checker = WindowChecker(listOf("06:30-09:30", "17:30-20:30"))

    private fun kst(text: String): Instant = LocalDateTime.parse("2026-09-21T$text").atZone(kst).toInstant()

    @Test
    fun `boundaries are inclusive to the minute on both ends`() {
        assertFalse(checker.isWithin(kst("06:29:59")))
        assertTrue(checker.isWithin(kst("06:30:00")))
        assertTrue(checker.isWithin(kst("09:30:00")))
        assertTrue(checker.isWithin(kst("09:30:59")))
        assertFalse(checker.isWithin(kst("09:31:00")))
        assertFalse(checker.isWithin(kst("12:00:00")))
        assertTrue(checker.isWithin(kst("17:30:00")))
        assertTrue(checker.isWithin(kst("20:30:59")))
        assertFalse(checker.isWithin(kst("20:31:00")))
    }

    @Test
    fun `instants are interpreted in Asia Seoul`() {
        assertTrue(checker.isWithin(Instant.parse("2026-09-20T21:30:00Z")))
        assertFalse(checker.isWithin(Instant.parse("2026-09-20T06:30:00Z")))
    }

    @Test
    fun `a single comma separated entry is split into windows`() {
        val single = WindowChecker(listOf("06:30-09:30,17:30-20:30"))

        assertEquals(2, single.windows.size)
        assertTrue(single.isWithin(kst("18:00:00")))
    }

    @Test
    fun `windows crossing midnight wrap around`() {
        val night = WindowChecker(listOf("23:00-01:00"))

        assertTrue(night.isWithin(kst("23:30:00")))
        assertTrue(night.isWithin(kst("00:30:00")))
        assertFalse(night.isWithin(kst("02:00:00")))
    }

    @Test
    fun `no windows means always within`() {
        assertTrue(WindowChecker(emptyList()).isWithin(kst("03:00:00")))
    }

    @Test
    fun `malformed windows are rejected`() {
        assertThrows<IllegalArgumentException> { WindowChecker(listOf("0630-0930")) }
        assertThrows<IllegalArgumentException> { WindowChecker(listOf("06:30")) }
    }
}
