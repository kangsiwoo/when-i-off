package com.kangsiwoo.whenioff.route.application

import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.route.api.LegCalibrationResponse
import com.kangsiwoo.whenioff.route.api.PredictionCalibrationRowResponse
import com.kangsiwoo.whenioff.route.api.RouteCalibrationResponse
import com.kangsiwoo.whenioff.route.api.TravelTimeCalibrationRowResponse
import com.kangsiwoo.whenioff.route.api.WalkingProfileResponse
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.transit.domain.TransitPredictionCalibrationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitTravelTimeCalibrationRepository
import com.kangsiwoo.whenioff.trip.domain.UserWalkingProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 경로의 캘리브레이션 상태 (#60). 보정 테이블은 analytics `calibrate`가 쓰고 backend는 읽기만 한다.
 * 고르는 순서(구간 행 → 상위 그룹 → 기본값)는 analytics `model/lookup.py`에 있고 여기서는 재현하지 않는다.
 */
@Service
@Transactional(readOnly = true)
class RouteCalibrationService(
    private val commuteRouteService: CommuteRouteService,
    private val routeLegRepository: RouteLegRepository,
    private val walkingProfileRepository: UserWalkingProfileRepository,
    private val predictionRepository: TransitPredictionCalibrationRepository,
    private val travelTimeRepository: TransitTravelTimeCalibrationRepository,
) {
    fun calibration(routeId: Long): RouteCalibrationResponse {
        val route = commuteRouteService.findOwned(routeId)
        val legs = routeLegRepository.findWithTransitByCommuteRoute(route)
        val walkIds = legs.filter { it.legType == LegType.WALK }.map { it.id!! }
        val profileByLeg =
            if (walkIds.isEmpty()) {
                emptyMap()
            } else {
                walkingProfileRepository
                    .findByUserIdAndRouteLegIdIn(DefaultUser.ID, walkIds)
                    .associateBy { it.routeLeg!!.id }
            }
        val global = walkingProfileRepository.findFirstByUserIdAndRouteLegIsNull(DefaultUser.ID)
        return RouteCalibrationResponse(
            routeId = route.id!!,
            minSamples = MIN_CALIBRATION_SAMPLES,
            globalWalkingProfile = global?.let(WalkingProfileResponse::from),
            legs = legs.map { leg -> toLeg(leg, profileByLeg[leg.id]?.let(WalkingProfileResponse::from)) },
        )
    }

    private fun toLeg(
        leg: RouteLeg,
        walkingProfile: WalkingProfileResponse?,
    ): LegCalibrationResponse {
        val line = leg.transitLine
        val board = leg.boardStop
        val alight = leg.alightStop
        // TRANSIT 구간은 노선·승하차 정류장이 다 있어야 보정 행의 키가 된다. 구간 수만큼 쿼리가 들지만
        // 경로 하나의 TRANSIT 구간은 한두 개다.
        val transit = leg.legType == LegType.TRANSIT
        val predictionRows =
            if (transit && line != null && board != null) {
                predictionRepository
                    .findByTransitLineIdAndStopId(line.id!!, board.id!!)
                    .sortedWith(compareBy({ it.dayType.ordinal }, { it.timeBandStart }))
                    .map(PredictionCalibrationRowResponse::from)
            } else {
                emptyList()
            }
        val travelTimeRows =
            if (transit && line != null && board != null && alight != null) {
                travelTimeRepository
                    .findByTransitLineIdAndBoardStopIdAndAlightStopId(line.id!!, board.id!!, alight.id!!)
                    .sortedWith(compareBy({ it.dayType.ordinal }, { it.timeBandStart }))
                    .map(TravelTimeCalibrationRowResponse::from)
            } else {
                emptyList()
            }
        return LegCalibrationResponse(
            routeLegId = leg.id!!,
            seqOrder = leg.seqOrder,
            legType = leg.legType,
            plannedDistanceM = leg.plannedDistanceM,
            plannedTravelSec = leg.plannedTravelSec,
            walkingProfile = if (leg.legType == LegType.WALK) walkingProfile else null,
            transitLineId = line?.id,
            transitLineName = line?.name,
            boardStopId = board?.id,
            boardStopName = board?.name,
            alightStopId = alight?.id,
            alightStopName = alight?.name,
            predictionRows = predictionRows,
            travelTimeRows = travelTimeRows,
        )
    }

    companion object {
        /** analytics `model/lookup.py`의 `MIN_CALIBRATION_SAMPLES`와 같은 값. 바꿀 때는 둘을 같이 바꾼다. */
        const val MIN_CALIBRATION_SAMPLES = 5
    }
}
