package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.trip.application.GpsTraceService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class GpsTraceController(
    private val service: GpsTraceService,
) {
    @PostMapping("/gps-traces/batch")
    fun saveBatch(
        @Valid @RequestBody request: GpsTraceBatchRequest,
    ): GpsTraceBatchResponse = service.saveBatch(DefaultUser.ID, request)

    /** trip에 묶인 GPS 포인트를 기록 시각 순으로 (#64). 내 trip이 아니거나 없으면 404. */
    @GetMapping("/commute-trips/{id}/gps-traces")
    fun tripTraces(
        @PathVariable id: Long,
    ): List<GpsTraceResponse> = service.tripTraces(DefaultUser.ID, id)
}
