package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.trip.application.CommuteTripService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1")
class CommuteTripController(
    private val service: CommuteTripService,
) {
    @PostMapping("/commute-trips")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateCommuteTripRequest,
    ): CommuteTripResponse = service.create(DefaultUser.ID, request)

    @PatchMapping("/commute-trips/{id}")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateCommuteTripRequest,
    ): CommuteTripResponse = service.update(DefaultUser.ID, id, request)

    @GetMapping("/commute-trips")
    fun search(
        @RequestParam(required = false) routeId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<CommuteTripResponse> = service.search(DefaultUser.ID, routeId, from, to)

    @PostMapping("/commute-trips/{id}/boarding-attempts")
    fun upsertBoardingAttempt(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpsertBoardingAttemptRequest,
    ): ResponseEntity<BoardingAttemptResponse> {
        val result = service.upsertBoardingAttempt(DefaultUser.ID, id, request)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(result.attempt)
    }

    @PatchMapping("/boarding-attempts/{id}")
    fun updateBoardingAttempt(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateBoardingAttemptRequest,
    ): BoardingAttemptResponse = service.updateBoardingAttempt(DefaultUser.ID, id, request)
}
