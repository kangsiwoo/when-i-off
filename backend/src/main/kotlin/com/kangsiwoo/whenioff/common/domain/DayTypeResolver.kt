package com.kangsiwoo.whenioff.common.domain

import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 날짜 → [DayType] 매핑. 시간표가 KST 기준이므로 판정도 KST 날짜로 한다.
 *
 * 공휴일은 특일정보 API 대신 리소스 파일의 수동 목록을 쓴다. 목록은 **연 1회 수동 갱신**이 전제이고
 * (DEVELOPMENT_PLAN Phase 3), 목록에 없는 해의 공휴일은 평일/토요일로 떨어진다.
 */
@Component
class DayTypeResolver {
    private val holidays: Set<LocalDate> = loadHolidays()

    fun resolve(date: LocalDate): DayType =
        when {
            date.dayOfWeek == DayOfWeek.SUNDAY || date in holidays -> DayType.SUNDAY_HOLIDAY
            date.dayOfWeek == DayOfWeek.SATURDAY -> DayType.SATURDAY
            else -> DayType.WEEKDAY
        }

    private fun loadHolidays(): Set<LocalDate> =
        checkNotNull(javaClass.classLoader.getResourceAsStream(HOLIDAY_RESOURCE)) {
            "holiday resource $HOLIDAY_RESOURCE missing"
        }.bufferedReader().useLines { lines ->
            lines
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map(LocalDate::parse)
                .toSet()
        }

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val HOLIDAY_RESOURCE = "calendar/kr-holidays.txt"
    }
}
