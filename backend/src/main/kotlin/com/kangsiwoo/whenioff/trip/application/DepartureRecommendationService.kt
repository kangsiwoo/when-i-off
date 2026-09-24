package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.trip.api.DepartureRecommendationResponse
import com.kangsiwoo.whenioff.trip.domain.DepartureRecommendationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 추천 조회. Backend는 추천을 계산하지 않고 analytics가 적재해 둔 값을 읽기만 한다
 * (API.md "추천 조회"). 추천이 아직 없으면 빈 200이 아니라 404다 — 호출자가 "값이 없다"와
 * "값이 0이다"를 구분할 수 있어야 한다.
 */
@Service
@Transactional(readOnly = true)
class DepartureRecommendationService(
    private val commuteRouteRepository: CommuteRouteRepository,
    private val recommendationRepository: DepartureRecommendationRepository,
) {
    fun forTargetArrival(
        userId: Long,
        routeId: Long,
        targetArrivalAt: Instant,
    ): DepartureRecommendationResponse {
        val route = findOwnedRoute(userId, routeId)
        val recommendation =
            recommendationRepository
                .findFirstByCommuteRouteAndTargetArrivalAtOrderByComputedAtDescIdDesc(route, targetArrivalAt)
                ?: throw NotFoundException(
                    "no recommendation for commute route $routeId with targetArrivalAt $targetArrivalAt",
                )
        return DepartureRecommendationResponse.from(recommendation)
    }

    fun latest(
        userId: Long,
        routeId: Long,
    ): DepartureRecommendationResponse {
        val route = findOwnedRoute(userId, routeId)
        val recommendation =
            recommendationRepository.findFirstByCommuteRouteOrderByComputedAtDescIdDesc(route)
                ?: throw NotFoundException("no recommendation for commute route $routeId")
        return DepartureRecommendationResponse.from(recommendation)
    }

    /** 다른 사용자의 경로는 존재 여부를 숨기고 404 (API.md 에러 규약). */
    private fun findOwnedRoute(
        userId: Long,
        routeId: Long,
    ): CommuteRoute =
        commuteRouteRepository.findByIdAndUserId(routeId, userId)
            ?: throw NotFoundException("commute route $routeId not found")
}
