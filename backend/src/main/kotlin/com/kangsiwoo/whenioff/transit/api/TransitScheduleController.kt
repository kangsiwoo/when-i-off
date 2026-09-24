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
    /**
     * `at`은 ISO-8601 절대 시각(기본값 현재). 응답도 절대 시각이라 호출자가 KST를 다룰 필요가 없다.
     *
     * `direction`은 필수 파라미터다 — 같은 정류장에 상·하행이 같이 서기 때문에, 서버가 대신
     * 추측하면 반대 방향 차가 "다음 차"로 나온다. 구간(승차→하차)에서 방향을 뽑는 쪽은
     * `LegDirectionResolver`이고, 이 엔드포인트는 그 결과를 받는다 (#26).
     */
    @GetMapping("/next")
    fun next(
        @PathVariable lineId: Long,
        @RequestParam stopId: Long,
        @RequestParam direction: String,
        @RequestParam(required = false) at: Instant?,
        @RequestParam(defaultValue = "${TransitScheduleService.DEFAULT_LIMIT}") limit: Int,
    ): NextDeparturesResponse =
        transitScheduleService.nextDepartures(lineId, stopId, direction, at ?: Instant.now(), limit)
}
