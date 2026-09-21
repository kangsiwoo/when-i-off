package com.kangsiwoo.whenioff.transit.domain

import com.kangsiwoo.whenioff.common.domain.DayType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalTime

interface TransitScheduleRepository : JpaRepository<TransitSchedule, Long> {
    /** `scheduledTime`은 KST 하루 중 시각이므로, 날짜를 붙여 절대 시각으로 바꾸는 것은 호출자 몫이다. */
    fun findByTransitLineIdAndStopIdAndDayTypeAndScheduledTimeGreaterThanEqualOrderByScheduledTimeAsc(
        transitLineId: Long,
        stopId: Long,
        dayType: DayType,
        scheduledTime: LocalTime,
        pageable: Pageable,
    ): List<TransitSchedule>

    fun findByTransitLineIdAndStopIdAndDayType(
        transitLineId: Long,
        stopId: Long,
        dayType: DayType,
    ): List<TransitSchedule>
}
