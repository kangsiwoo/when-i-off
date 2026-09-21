package com.kangsiwoo.whenioff.common.domain

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class DayTypeResolverTest {
    private val resolver = DayTypeResolver()

    @Test
    fun `maps weekday saturday and sunday`() {
        assertEquals(DayType.WEEKDAY, resolver.resolve(LocalDate.parse("2026-05-01")))
        assertEquals(DayType.SATURDAY, resolver.resolve(LocalDate.parse("2026-05-02")))
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2026-05-03")))
    }

    @Test
    fun `listed holidays and substitute holidays count as sunday`() {
        // 어린이날(화), 삼일절 대체공휴일(월). 근로자의날(5/1)은 법정공휴일이 아니라 평일이다.
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2026-05-05")))
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2026-03-02")))
    }
}
