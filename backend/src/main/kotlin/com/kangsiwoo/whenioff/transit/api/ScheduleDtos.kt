package com.kangsiwoo.whenioff.transit.api

import com.kangsiwoo.whenioff.common.domain.DayType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class NextDeparturesResponse(
    val transitLineId: Long,
    val stopId: Long,
    val departures: List<ScheduledDepartureResponse>,
)

/**
 * 시간표 한 편. `serviceDate`/`scheduledTime`은 KST 기준 운행일과 하루 중 시각이고,
 * 실제로 쓰는 값은 둘을 합쳐 UTC로 바꾼 `departureAt`이다.
 */
data class ScheduledDepartureResponse(
    val serviceDate: LocalDate,
    val dayType: DayType,
    val scheduledTime: LocalTime,
    val departureAt: Instant,
)
