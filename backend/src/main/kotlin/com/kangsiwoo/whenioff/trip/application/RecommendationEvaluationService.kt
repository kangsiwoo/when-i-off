package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.route.application.CommuteRouteService
import com.kangsiwoo.whenioff.trip.api.RecommendationEvaluationResponse
import com.kangsiwoo.whenioff.trip.api.RecommendationEvaluationSummaryResponse
import com.kangsiwoo.whenioff.trip.api.RecommendationEvaluationsResponse
import com.kangsiwoo.whenioff.trip.domain.RecommendationEvaluation
import com.kangsiwoo.whenioff.trip.domain.RecommendationEvaluationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 추천 성과 평가 조회 (#79). 행은 analytics `evaluate`(#72)가 쌓은 것을 그대로 주고, 버전별 요약만 여기서 낸다.
 * 요약 정의는 analytics `summarize()`와 같아야 CLI 출력과 화면 숫자가 같다.
 */
@Service
@Transactional(readOnly = true)
class RecommendationEvaluationService(
    private val commuteRouteService: CommuteRouteService,
    private val evaluationRepository: RecommendationEvaluationRepository,
) {
    fun evaluations(
        routeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): RecommendationEvaluationsResponse {
        if (to.isBefore(from)) throw BadRequestException("to must not be before from")
        val route = commuteRouteService.findOwned(routeId)
        val rows =
            evaluationRepository.findByCommuteRouteIdAndTargetDateBetweenOrderByTargetDateAscModelVersionAsc(
                route.id!!,
                from,
                to,
            )
        return RecommendationEvaluationsResponse(
            summaries = summarize(rows),
            evaluations = rows.map(RecommendationEvaluationResponse::from),
        )
    }

    companion object {
        /** analytics `model/evaluation.py`의 `summarize()`와 같은 정의 + 전 구간 탑승률. 버전 문자열 순. */
        fun summarize(rows: List<RecommendationEvaluation>): List<RecommendationEvaluationSummaryResponse> =
            rows
                .groupBy { it.modelVersion }
                .toSortedMap()
                .map { (version, group) ->
                    val decided = group.mapNotNull { it.isLate }
                    val late = decided.count { it }
                    val caught = group.count { it.allLegsCaught }
                    RecommendationEvaluationSummaryResponse(
                        modelVersion = version,
                        n = group.size,
                        lateCount = late,
                        withArrival = decided.size,
                        lateRate = if (decided.isEmpty()) null else late.toDouble() / decided.size,
                        meanDepartureDiffSec = mean(group.map { it.departureDiffSec }),
                        meanStopWaitSec = mean(group.map { it.avgStopWaitSec }),
                        allLegsCaughtCount = caught,
                        allLegsCaughtRate = caught.toDouble() / group.size,
                    )
                }

        private fun mean(values: List<Int?>): Double? {
            val present = values.filterNotNull()
            return if (present.isEmpty()) null else present.sum().toDouble() / present.size
        }
    }
}
