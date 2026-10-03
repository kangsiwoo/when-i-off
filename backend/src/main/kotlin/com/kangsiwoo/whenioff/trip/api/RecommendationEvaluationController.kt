package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.trip.application.RecommendationEvaluationService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/commute-routes/{routeId}")
class RecommendationEvaluationController(
    private val service: RecommendationEvaluationService,
) {
    /**
     * 추천 성과 평가 (#79). `from`·`to`는 필수 KST 날짜(`yyyy-MM-dd`, 양끝 포함, `target_date` 기준). `to < from`이면
     * 400. 기간의 평가 행과 `modelVersion`별 요약을 준다. 평가가 없으면 둘 다 빈 배열. 내 경로가 아니거나 없으면 404.
     */
    @GetMapping("/recommendation-evaluations")
    fun recommendationEvaluations(
        @PathVariable routeId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): RecommendationEvaluationsResponse = service.evaluations(routeId, from, to)
}
