package com.kangsiwoo.whenioff.trip.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.kangsiwoo.whenioff.trip.domain.RecommendationEvaluation
import java.time.Instant
import java.time.LocalDate

/** 추천 성과 평가 (#79, API.md "추천 성과 평가"). 기간의 평가 행과 `modelVersion`별 요약. */
data class RecommendationEvaluationsResponse(
    /** `modelVersion` 순(문자열 순, analytics `evaluate` 요약과 같다). 평가가 없으면 빈 배열. */
    val summaries: List<RecommendationEvaluationSummaryResponse>,
    /** 날짜 → `modelVersion` 순. */
    val evaluations: List<RecommendationEvaluationResponse>,
)

/**
 * 버전 하나의 요약. analytics `model/evaluation.py`의 `summarize()`와 같은 정의다: 지각률의 분모는 도착 기록이
 * 있는 평가 수(`withArrival`), 평균은 값이 있는 행만으로 낸다. 분모가 0이면 비율·평균은 빠진다(null).
 */
data class RecommendationEvaluationSummaryResponse(
    val modelVersion: String,
    /** 평가 수 (= 평가된 날 수. 경로·날짜·버전마다 한 행). */
    val n: Int,
    /** 지각(`isLate = true`) 수. */
    val lateCount: Int,
    /** 도착 기록이 있는(`isLate`가 null이 아닌) 평가 수. 지각률의 분모. */
    val withArrival: Int,
    /** `lateCount / withArrival`, 0~1. `withArrival = 0`이면 null. */
    val lateRate: Double?,
    /** 출발 차이(실제 − 추천, 초) 평균. 값이 있는 행만. */
    val meanDepartureDiffSec: Double?,
    /** 정류장 평균 대기(초)의 평균. 값이 있는 행만. */
    val meanStopWaitSec: Double?,
    /** `allLegsCaught = true` 수. */
    val allLegsCaughtCount: Int,
    /** `allLegsCaughtCount / n`, 0~1. */
    val allLegsCaughtRate: Double,
)

data class RecommendationEvaluationResponse(
    /** KST 날짜 (`target_date`). */
    val date: LocalDate,
    val modelVersion: String,
    val targetArrivalAt: Instant,
    val recommendedLeaveHomeAt: Instant,
    val actualLeftHomeAt: Instant?,
    val actualArrivedAt: Instant?,
    /** 실제 출발 − 추천 출발(초). + = 늦게 나감. */
    val departureDiffSec: Int?,
    /** 실제 도착 − 목표 도착(초). + = 늦음. */
    val arrivalDiffSec: Int?,
    /** 실제 도착 > 목표 도착 (정각은 지각 아님). 도착 기록이 없으면 빠진다. */
    @get:JsonProperty("isLate")
    val isLate: Boolean?,
    val allLegsCaught: Boolean,
    val missedCount: Int,
    /** `CAUGHT` 구간마다 (탄 차 실제 출발 − 첫 시도 정류장 도착)의 구간 평균(초). */
    val avgStopWaitSec: Int?,
    val evaluatedAt: Instant,
) {
    companion object {
        fun from(e: RecommendationEvaluation) =
            RecommendationEvaluationResponse(
                date = e.targetDate,
                modelVersion = e.modelVersion,
                targetArrivalAt = e.targetArrivalAt,
                recommendedLeaveHomeAt = e.recommendedLeaveHomeAt,
                actualLeftHomeAt = e.actualLeftHomeAt,
                actualArrivedAt = e.actualArrivedAt,
                departureDiffSec = e.departureDiffSec,
                arrivalDiffSec = e.arrivalDiffSec,
                isLate = e.isLate,
                allLegsCaught = e.allLegsCaught,
                missedCount = e.missedCount,
                avgStopWaitSec = e.avgStopWaitSec,
                evaluatedAt = e.evaluatedAt,
            )
    }
}
