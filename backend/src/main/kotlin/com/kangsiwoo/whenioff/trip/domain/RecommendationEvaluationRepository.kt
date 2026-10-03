package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface RecommendationEvaluationRepository : JpaRepository<RecommendationEvaluation, Long> {
    /** 기간(KST `target_date`, 양끝 포함)의 평가 전부, 날짜 → 버전 순. (경로, 날짜, 버전)이 UNIQUE라 동점이 없다. */
    fun findByCommuteRouteIdAndTargetDateBetweenOrderByTargetDateAscModelVersionAsc(
        commuteRouteId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<RecommendationEvaluation>
}
