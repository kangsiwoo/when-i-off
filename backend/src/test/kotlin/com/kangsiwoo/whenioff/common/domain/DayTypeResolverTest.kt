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

    @Test
    fun `chuseok holidays falling on a saturday get no substitute day`() {
        // 2026 추석 연휴는 9/24(목)·25(금)·26(토)라 일요일이 없다. 설날·추석 연휴의 대체공휴일은
        // 일요일이나 다른 공휴일과 겹칠 때만 생기므로(토요일은 어린이날·국경일만 해당),
        // 9/28(월)은 평일이다. 한때 목록에 대체공휴일로 잘못 들어가 있었다 (#28).
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2026-09-25")))
        // 26일은 토요일이지만 추석 다음 날이라 목록에 있다 — 공휴일이 토요일을 이긴다.
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2026-09-26")))
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2026-09-27")))
        assertEquals(DayType.WEEKDAY, resolver.resolve(LocalDate.parse("2026-09-28")))
    }

    @Test
    fun `constitution day is a holiday again from 2026`() {
        // 2008년 공휴일에서 빠졌던 제헌절이 2026년부터 재지정됐다 (#28). 7/17은 금요일이라
        // 대체공휴일은 없다 — 제헌절 대체공휴일 적용은 2027년부터다.
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2026-07-17")))
        assertEquals(DayType.WEEKDAY, resolver.resolve(LocalDate.parse("2026-07-20")))
    }
}
