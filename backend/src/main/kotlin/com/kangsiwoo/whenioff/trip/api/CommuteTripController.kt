package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.trip.application.CommuteTripService
import jakarta.validation.Valid
import org.springframework.dao.DataIntegrityViolationException
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
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1")
class CommuteTripController(
    private val service: CommuteTripService,
) {
    /** 새로 만들면 201, 같은 (경로, `leftHomeAt`)의 재전송이면 기존 trip을 200으로 돌려준다 (#37). */
    @PostMapping("/commute-trips")
    fun create(
        @Valid @RequestBody request: CreateCommuteTripRequest,
    ): ResponseEntity<CommuteTripResponse> {
        val result =
            try {
                service.create(DefaultUser.ID, request)
            } catch (e: DataIntegrityViolationException) {
                // 같은 재전송 둘이 동시에 들어오면 둘 다 "없음"을 보고 삽입하고, 진 쪽이 유일 인덱스(V4)에 걸린다.
                // 한 번 다시 타면 이긴 쪽 trip을 찾아 돌려준다 — 탑승 시도 upsert와 같은 방식이다.
                service.create(DefaultUser.ID, request)
            }
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(result.trip)
    }

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
        val result =
            try {
                service.upsertBoardingAttempt(DefaultUser.ID, id, request)
            } catch (e: DataIntegrityViolationException) {
                // 같은 (trip, leg, attemptSeq)의 첫 요청 둘이 동시에 들어오면 진 쪽이 UNIQUE(V5)에 걸리므로 갱신 경로로 한 번 다시 탄다.
                service.upsertBoardingAttempt(DefaultUser.ID, id, request)
            }
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(result.attempt)
    }

    @PatchMapping("/boarding-attempts/{id}")
    fun updateBoardingAttempt(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateBoardingAttemptRequest,
    ): BoardingAttemptResponse = service.updateBoardingAttempt(DefaultUser.ID, id, request)
}
