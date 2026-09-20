package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.trip.application.GpsTraceService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/gps-traces")
class GpsTraceController(
    private val service: GpsTraceService,
) {
    @PostMapping("/batch")
    fun saveBatch(
        @Valid @RequestBody request: GpsTraceBatchRequest,
    ): GpsTraceBatchResponse = service.saveBatch(DefaultUser.ID, request)
}
