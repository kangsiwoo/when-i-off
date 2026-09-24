package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.trip.domain.DepartureRecommendation
import java.time.Instant

/**
 * Analytics가 미리 계산해 `departure_recommendations`에 적재해 둔 추천 한 건.
 * Backend는 계산하지 않고 읽기만 한다 (API.md "추천 조회").
 */
data class DepartureRecommendationResponse(
    val recommendedLeaveHomeAt: Instant,
    val targetArrivalAt: Instant,
    val catchProbability: Double,
    val bufferSeconds: Int,
    val modelVersion: String,
    val computedAt: Instant,
) {
    companion object {
        fun from(recommendation: DepartureRecommendation) =
            DepartureRecommendationResponse(
                recommendedLeaveHomeAt = recommendation.recommendedLeaveHomeAt,
                targetArrivalAt = recommendation.targetArrivalAt,
                catchProbability = recommendation.catchProbability,
                bufferSeconds = recommendation.bufferSeconds,
                modelVersion = recommendation.modelVersion,
                computedAt = recommendation.computedAt,
            )
    }
}
