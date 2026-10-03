package com.kangsiwoo.whenioff.signal.api

import com.kangsiwoo.whenioff.signal.application.TrafficSignalCycleService
import com.kangsiwoo.whenioff.signal.application.TrafficSignalService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/traffic-signals")
class TrafficSignalController(
    private val trafficSignalService: TrafficSignalService,
    private val cycleService: TrafficSignalCycleService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateTrafficSignalRequest,
    ): TrafficSignalResponse = trafficSignalService.create(request)

    @GetMapping("/nearby")
    fun nearby(
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(required = false) radiusM: Double?,
    ): List<TrafficSignalResponse> = trafficSignalService.nearby(lat, lng, radiusM)

    /** 그 교차로의 주기 행 전부(출처 무관). day_type → 시간대 시작 → 출처 우선순위 순. */
    @GetMapping("/{id}/cycles")
    fun listCycles(
        @PathVariable id: Long,
    ): List<TrafficSignalCycleResponse> = cycleService.list(id)

    /** `USER_OBSERVED` 행만 통째로 교체한다. 다른 출처 행은 그대로. 응답은 교체 후 GET과 같다. */
    @PutMapping("/{id}/cycles")
    fun replaceCycles(
        @PathVariable id: Long,
        @Valid @RequestBody request: ReplaceTrafficSignalCyclesRequest,
    ): List<TrafficSignalCycleResponse> = cycleService.replaceUserObserved(id, request.cycles)
}
