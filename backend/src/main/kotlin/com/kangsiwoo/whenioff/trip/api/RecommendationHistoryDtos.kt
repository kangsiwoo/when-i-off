package com.kangsiwoo.whenioff.trip.api

import com.kangsiwoo.whenioff.trip.domain.BoardingAttempt
import com.kangsiwoo.whenioff.trip.domain.BoardingResult
import com.kangsiwoo.whenioff.trip.domain.CommuteTrip
import java.time.Instant
import java.time.LocalDate

/**
 * 추천 vs 실제 하루치 (#62, API.md "추천 이력"). 추천과 trip을 날짜(KST)로 묶기만 하고 차이·지각 같은 파생값은
 * 화면이 계산한다.
 */
data class RecommendationHistoryDayResponse(
    /** KST 날짜. 추천은 `target_date`, trip은 `trip_date`로 묶는다. */
    val date: LocalDate,
    /**
     * `modelVersion`마다 그날 **마지막으로 계산된** 추천 한 건(`computedAt DESC, id DESC`), `modelVersion` 순.
     * 그날 목표 시각이 여러 개여도 버전당 한 건이고, 어느 목표 시각의 것인지는 `targetArrivalAt`이 말한다.
     */
    val recommendations: List<DepartureRecommendationResponse>,
    /** 그날 이 경로의 trip 전부. `leftHomeAt` 순(없으면 뒤), 같으면 id 순. */
    val trips: List<RecommendationHistoryTripResponse>,
)

data class RecommendationHistoryTripResponse(
    val tripId: Long,
    val leftHomeAt: Instant?,
    val arrivedDestinationAt: Instant?,
    /** 경로의 TRANSIT 구간마다 `CAUGHT` 시도가 있으면 true. TRANSIT 구간이 없는 경로는 true다. */
    val allLegsCaught: Boolean,
    /** `MISSED` 시도 수 (놓친 차 대수). */
    val missedCount: Int,
) {
    companion object {
        fun from(
            trip: CommuteTrip,
            attempts: List<BoardingAttempt>,
            transitLegIds: Set<Long>,
        ): RecommendationHistoryTripResponse {
            val caughtLegs = attempts.filter { it.result == BoardingResult.CAUGHT }.map { it.routeLeg.id!! }.toSet()
            return RecommendationHistoryTripResponse(
                tripId = trip.id!!,
                leftHomeAt = trip.leftHomeAt,
                arrivedDestinationAt = trip.arrivedDestinationAt,
                allLegsCaught = caughtLegs.containsAll(transitLegIds),
                missedCount = attempts.count { it.result == BoardingResult.MISSED },
            )
        }
    }
}
