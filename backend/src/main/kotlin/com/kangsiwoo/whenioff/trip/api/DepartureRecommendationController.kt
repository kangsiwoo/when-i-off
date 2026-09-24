package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.trip.application.DepartureRecommendationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/commute-routes/{routeId}/recommendation")
class DepartureRecommendationController(
    private val service: DepartureRecommendationService,
) {
    /** `targetArrivalAt`은 필수 ISO-8601 절대 시각. 없거나 파싱 실패면 400. */
    @GetMapping
    fun forTargetArrival(
        @PathVariable routeId: Long,
        @RequestParam targetArrivalAt: Instant,
    ): DepartureRecommendationResponse = service.forTargetArrival(DefaultUser.ID, routeId, targetArrivalAt)

    /** 목표 시각과 무관하게 가장 최근 계산된 한 건. */
    @GetMapping("/latest")
    fun latest(
        @PathVariable routeId: Long,
    ): DepartureRecommendationResponse = service.latest(DefaultUser.ID, routeId)
}
