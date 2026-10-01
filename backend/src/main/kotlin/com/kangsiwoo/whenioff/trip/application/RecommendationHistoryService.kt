package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.route.application.CommuteRouteService
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.trip.api.DepartureRecommendationResponse
import com.kangsiwoo.whenioff.trip.api.RecommendationHistoryDayResponse
import com.kangsiwoo.whenioff.trip.api.RecommendationHistoryTripResponse
import com.kangsiwoo.whenioff.trip.domain.BoardingAttemptRepository
import com.kangsiwoo.whenioff.trip.domain.CommuteTripRepository
import com.kangsiwoo.whenioff.trip.domain.DepartureRecommendationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 추천 vs 실제 (#62). 날짜(KST)마다 버전별 마지막 추천과 그날 trip을 묶는다.
 *
 * analytics는 `(경로, target_date, target_arrival_at, model_version)`당 한 행을 제자리에서 갱신하고(`computed_at`도
 * 바뀐다) 예전 backend 데이터에는 같은 키의 행이 여럿 쌓여 있을 수 있다. 어느 쪽이든 (날짜, 버전)에서
 * `computed_at DESC, id DESC` 첫 행이 "그날 마지막으로 계산된 추천"이다 — 목표 시각이 여러 개인 날도 마찬가지다.
 */
@Service
@Transactional(readOnly = true)
class RecommendationHistoryService(
    private val commuteRouteService: CommuteRouteService,
    private val routeLegRepository: RouteLegRepository,
    private val recommendationRepository: DepartureRecommendationRepository,
    private val commuteTripRepository: CommuteTripRepository,
    private val boardingAttemptRepository: BoardingAttemptRepository,
) {
    fun history(
        routeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<RecommendationHistoryDayResponse> {
        if (to.isBefore(from)) throw BadRequestException("to must not be before from")
        val route = commuteRouteService.findOwned(routeId)

        val recommendationsByDate =
            recommendationRepository
                .findByCommuteRouteAndTargetDateBetweenOrderByTargetDateAscModelVersionAscComputedAtDescIdDesc(
                    route,
                    from,
                    to,
                ).groupBy { it.targetDate }
                // 정렬이 버전 안에서 최신순이라 버전마다 첫 행이 마지막 계산이다.
                .mapValues { (_, rows) ->
                    rows.distinctBy { it.modelVersion }.map(DepartureRecommendationResponse::from)
                }

        val trips =
            commuteTripRepository.findByCommuteRouteIdAndTripDateBetweenOrderByTripDateAscIdAsc(
                route.id!!,
                from,
                to,
            )
        val attemptsByTrip =
            if (trips.isEmpty()) {
                emptyMap()
            } else {
                boardingAttemptRepository
                    .findByCommuteTripIdInOrderByRouteLegSeqOrderAscAttemptSeqAsc(trips.map { it.id!! })
                    .groupBy { it.commuteTrip.id!! }
            }
        val transitLegIds =
            routeLegRepository
                .findByCommuteRouteOrderBySeqOrderAsc(route)
                .filter { it.legType == LegType.TRANSIT }
                .map { it.id!! }
                .toSet()
        val tripsByDate =
            trips
                .sortedWith(compareBy({ it.tripDate }, { it.leftHomeAt == null }, { it.leftHomeAt }, { it.id }))
                .groupBy({ it.tripDate }) {
                    RecommendationHistoryTripResponse.from(it, attemptsByTrip[it.id].orEmpty(), transitLegIds)
                }

        return (recommendationsByDate.keys + tripsByDate.keys).sorted().map { date ->
            RecommendationHistoryDayResponse(
                date = date,
                recommendations = recommendationsByDate[date].orEmpty(),
                trips = tripsByDate[date].orEmpty(),
            )
        }
    }
}
