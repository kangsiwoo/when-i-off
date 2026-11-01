package com.kangsiwoo.whenioff.common.domain

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 날짜 → [DayType] 매핑. 시간표가 KST 기준이므로 판정도 KST 날짜로 한다.
 *
 * 공휴일은 특일정보 API 대신 리소스 파일의 수동 목록을 쓴다. 목록은 **연 1회 수동 갱신**이 전제이고
 * (DEVELOPMENT_PLAN Phase 3), 목록에 없는 해의 공휴일은 평일/토요일로 떨어진다. 갱신을 잊으면 조용히
 * 틀리므로, 기동 시 [EXPIRY_WARNING_DAYS]일 안에 목록이 끝나면 경고하고, 목록에 없는 해를 판정할 때도
 * 해마다 한 번 경고한다.
 */
@Component
class DayTypeResolver internal constructor(
    private val holidays: Set<LocalDate>,
    today: LocalDate,
) {
    constructor() : this(loadHolidays(), LocalDate.now(KST))

    /** 목록에 공휴일이 하나라도 있는 해. 해마다 공휴일이 있으므로 이것이 목록이 덮는 해다. */
    val coveredYears: Set<Int> = holidays.mapTo(sortedSetOf()) { it.year }

    private val warnedYears: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    init {
        val horizon = today.plusDays(EXPIRY_WARNING_DAYS)
        val missing = (today.year..horizon.year).filterNot { it in coveredYears }
        if (missing.isNotEmpty()) {
            log.warn {
                "holiday list $HOLIDAY_RESOURCE has no entries for $missing (within $EXPIRY_WARNING_DAYS days of " +
                    "$today); holidays in those years will be treated as weekdays. Add them before they start."
            }
        }
    }

    fun resolve(date: LocalDate): DayType {
        if (date.year !in coveredYears && warnedYears.add(date.year)) {
            log.warn {
                "holiday list $HOLIDAY_RESOURCE has no entries for ${date.year}; resolving $date without holidays"
            }
        }
        return when {
            date.dayOfWeek == DayOfWeek.SUNDAY || date in holidays -> DayType.SUNDAY_HOLIDAY
            date.dayOfWeek == DayOfWeek.SATURDAY -> DayType.SATURDAY
            else -> DayType.WEEKDAY
        }
    }

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val HOLIDAY_RESOURCE = "calendar/kr-holidays.txt"

        /** 목록이 이 기간 안에 끝나면 기동 시 경고한다. 연말 갱신을 잊지 않게 10월 초부터 알린다. */
        const val EXPIRY_WARNING_DAYS = 90L

        internal fun loadHolidays(): Set<LocalDate> =
            checkNotNull(DayTypeResolver::class.java.classLoader.getResourceAsStream(HOLIDAY_RESOURCE)) {
                "holiday resource $HOLIDAY_RESOURCE missing"
            }.bufferedReader().useLines { lines ->
                lines
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .map(LocalDate::parse)
                    .toSet()
            }
    }
}
