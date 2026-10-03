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
    /**
     * 이 확률 뒤에 있는 실측 표본 수 (#86). TRANSIT 구간마다 고른 차량의 예측 오차·차내 시간 입력이 기댄 표본 수 중
     * 최솟값이다. 0이면 어느 입력이 콜드스타트 기본값으로 내려갔다는 뜻이고, 0이 아니면 analytics의 사용 기준
     * (`MIN_CALIBRATION_SAMPLES` = 5) 이상이다. 표본 수를 기록하기 전(V8 이전)에 계산된 추천이나 TRANSIT 구간이
     * 없는 경로면 키가 빠진다.
     */
    val minTransitSampleCount: Int? = null,
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
                minTransitSampleCount = recommendation.minTransitSampleCount,
            )
    }
}
