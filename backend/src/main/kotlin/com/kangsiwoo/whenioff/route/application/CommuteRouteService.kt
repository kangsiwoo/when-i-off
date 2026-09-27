package com.kangsiwoo.whenioff.route.application

import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.route.api.CommuteRouteDetailResponse
import com.kangsiwoo.whenioff.route.api.CommuteRouteResponse
import com.kangsiwoo.whenioff.route.api.CreateCommuteRouteRequest
import com.kangsiwoo.whenioff.route.api.RouteLegResponse
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossingRepository
import com.kangsiwoo.whenioff.user.domain.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CommuteRouteService(
    private val userRepository: UserRepository,
    private val commuteRouteRepository: CommuteRouteRepository,
    private val routeLegRepository: RouteLegRepository,
    private val signalCrossingRepository: RouteLegSignalCrossingRepository,
) {
    @Transactional(readOnly = true)
    fun list(): List<CommuteRouteResponse> =
        commuteRouteRepository.findByUserIdOrderByIdAsc(DefaultUser.ID).map(CommuteRouteResponse::from)

    fun create(request: CreateCommuteRouteRequest): CommuteRouteResponse {
        val user = userRepository.getReferenceById(DefaultUser.ID)
        val route =
            CommuteRoute(
                user = user,
                name = request.name.trim(),
                direction = request.direction,
                originLat = request.originLat,
                originLng = request.originLng,
                destinationLat = request.destinationLat,
                destinationLng = request.destinationLng,
                isActive = request.isActive,
            )
        return CommuteRouteResponse.from(commuteRouteRepository.save(route))
    }

    @Transactional(readOnly = true)
    fun detail(routeId: Long): CommuteRouteDetailResponse = toDetail(findOwned(routeId))

    fun findOwned(routeId: Long): CommuteRoute =
        commuteRouteRepository.findByIdAndUserId(routeId, DefaultUser.ID)
            ?: throw NotFoundException("commute route $routeId not found")

    fun toDetail(route: CommuteRoute): CommuteRouteDetailResponse {
        val legs = routeLegRepository.findWithTransitByCommuteRoute(route)
        val crossingsByLeg =
            if (legs.isEmpty()) {
                emptyMap()
            } else {
                signalCrossingRepository.findByRouteLegInOrderBySeqOrderAsc(legs).groupBy { it.routeLeg.id }
            }
        return CommuteRouteDetailResponse(
            route = CommuteRouteResponse.from(route),
            legs = legs.map { RouteLegResponse.from(it, crossingsByLeg[it.id].orEmpty()) },
        )
    }
}
