package com.kangsiwoo.whenioff.transit.domain

import com.kangsiwoo.whenioff.common.domain.DayType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalTime

interface TransitScheduleRepository : JpaRepository<TransitSchedule, Long> {
    /**
     * `scheduledTime`은 KST 하루 중 시각이므로, 날짜를 붙여 절대 시각으로 바꾸는 것은 호출자 몫이다.
     * 한 정류장에는 상·하행이 같이 서므로 `directionCode`까지 걸러야 "다음 차"가 섞이지 않는다 (#26).
     */
    @Query(
        """
        select s from TransitSchedule s
        where s.transitLine.id = :lineId
          and s.stop.id = :stopId
          and s.dayType = :dayType
          and s.directionCode = :directionCode
          and s.scheduledTime >= :from
        order by s.scheduledTime asc
        """,
    )
    fun findFrom(
        @Param("lineId") lineId: Long,
        @Param("stopId") stopId: Long,
        @Param("dayType") dayType: DayType,
        @Param("directionCode") directionCode: String,
        @Param("from") from: LocalTime,
        pageable: Pageable,
    ): List<TransitSchedule>

    /** import의 멱등성 단위이기도 하다 — (노선, 정류장, day_type, 방향)만 지우고 다시 넣는다. */
    fun findByTransitLineIdAndStopIdAndDayTypeAndDirectionCode(
        transitLineId: Long,
        stopId: Long,
        dayType: DayType,
        directionCode: String,
    ): List<TransitSchedule>
}
