package com.kangsiwoo.whenioff.signal.api

import com.kangsiwoo.whenioff.signal.application.TrafficSignalService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/traffic-signals")
class TrafficSignalController(
    private val trafficSignalService: TrafficSignalService,
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
}
