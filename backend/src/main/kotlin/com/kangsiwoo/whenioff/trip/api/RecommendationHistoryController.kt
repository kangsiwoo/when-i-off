package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.trip.application.RecommendationHistoryService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/commute-routes/{routeId}")
class RecommendationHistoryController(
    private val service: RecommendationHistoryService,
) {
    /**
     * 추천 vs 실제 (#62). `from`·`to`는 필수 KST 날짜(`yyyy-MM-dd`, 양끝 포함). `to < from`이면 400.
     * 추천이나 trip 중 하나라도 있는 날만 날짜 오름차순으로 준다. 내 경로가 아니거나 없으면 404.
     */
    @GetMapping("/recommendation-history")
    fun recommendationHistory(
        @PathVariable routeId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<RecommendationHistoryDayResponse> = service.history(routeId, from, to)
}
