package com.kangsiwoo.whenioff.transit.api

import com.kangsiwoo.whenioff.transit.application.TransitScheduleService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/transit-lines/{lineId}/schedules")
class TransitScheduleController(
    private val transitScheduleService: TransitScheduleService,
) {
    /** `at`은 ISO-8601 절대 시각(기본값 현재). 응답도 절대 시각이라 호출자가 KST를 다룰 필요가 없다. */
    @GetMapping("/next")
    fun next(
        @PathVariable lineId: Long,
        @RequestParam stopId: Long,
        @RequestParam(required = false) at: Instant?,
        @RequestParam(defaultValue = "${TransitScheduleService.DEFAULT_LIMIT}") limit: Int,
    ): NextDeparturesResponse = transitScheduleService.nextDepartures(lineId, stopId, at ?: Instant.now(), limit)
}
