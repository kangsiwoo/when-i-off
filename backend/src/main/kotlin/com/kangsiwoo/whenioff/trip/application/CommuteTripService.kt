package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.trip.api.BoardingAttemptChanges
import com.kangsiwoo.whenioff.trip.api.BoardingAttemptResponse
import com.kangsiwoo.whenioff.trip.api.CommuteTripResponse
import com.kangsiwoo.whenioff.trip.api.CreateCommuteTripRequest
import com.kangsiwoo.whenioff.trip.api.UpdateBoardingAttemptRequest
import com.kangsiwoo.whenioff.trip.api.UpdateCommuteTripRequest
import com.kangsiwoo.whenioff.trip.api.UpsertBoardingAttemptRequest
import com.kangsiwoo.whenioff.trip.domain.BoardingAttempt
import com.kangsiwoo.whenioff.trip.domain.BoardingAttemptRepository
import com.kangsiwoo.whenioff.trip.domain.BoardingResult
import com.kangsiwoo.whenioff.trip.domain.CommuteTrip
import com.kangsiwoo.whenioff.trip.domain.CommuteTripRepository
import com.kangsiwoo.whenioff.user.domain.User
import com.kangsiwoo.whenioff.user.domain.UserRepository
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

data class UpsertResult(
    val attempt: BoardingAttemptResponse,
    val created: Boolean,
)

@Service
@Transactional
class CommuteTripService(
    private val userRepository: UserRepository,
    private val commuteRouteRepository: CommuteRouteRepository,
    private val routeLegRepository: RouteLegRepository,
    private val commuteTripRepository: CommuteTripRepository,
    private val boardingAttemptRepository: BoardingAttemptRepository,
) {
    fun create(
        userId: Long,
        request: CreateCommuteTripRequest,
    ): CommuteTripResponse {
        val route =
            commuteRouteRepository
                .findByIdOrNull(request.routeId)
                ?.takeIf { it.user.id == userId }
                ?: throw NotFoundException("commute route ${request.routeId} not found")
        val trip =
            commuteTripRepository.save(
                CommuteTrip(
                    user = userRepository.getReferenceById(userId),
                    commuteRoute = route,
                    tripDate = request.tripDate,
                    leftHomeAt = request.leftHomeAt,
                ),
            )
        return CommuteTripResponse.from(trip, emptyList())
    }

    fun update(
        userId: Long,
        tripId: Long,
        request: UpdateCommuteTripRequest,
    ): CommuteTripResponse {
        val trip = findTrip(userId, tripId)
        request.leftHomeAt?.let { trip.leftHomeAt = it }
        request.arrivedDestinationAt?.let { trip.arrivedDestinationAt = it }
        requireOrdered(trip.leftHomeAt, trip.arrivedDestinationAt, "arrivedDestinationAt must not be before leftHomeAt")
        return CommuteTripResponse.from(trip, attemptsOf(trip))
    }

    fun upsertBoardingAttempt(
        userId: Long,
        tripId: Long,
        request: UpsertBoardingAttemptRequest,
    ): UpsertResult {
        val trip = findTrip(userId, tripId)
        val existing = boardingAttemptRepository.findByCommuteTripIdAndRouteLegId(tripId, request.routeLegId)
        val attempt =
            existing?.apply { applyChanges(request) }
                ?: boardingAttemptRepository.save(
                    BoardingAttempt(
                        commuteTrip = trip,
                        routeLeg = transitLegOf(trip, request.routeLegId),
                        arrivedAtStopAt = request.arrivedAtStopAt,
                        vehicleScheduledOrPredictedAt = request.vehicleScheduledOrPredictedAt,
                        vehicleActualDepartureAt = request.vehicleActualDepartureAt,
                        alightedAt = request.alightedAt,
                        result = request.result ?: BoardingResult.UNKNOWN,
                        notes = request.notes,
                    ),
                )
        return UpsertResult(BoardingAttemptResponse.from(attempt), created = existing == null)
    }

    fun updateBoardingAttempt(
        userId: Long,
        attemptId: Long,
        request: UpdateBoardingAttemptRequest,
    ): BoardingAttemptResponse {
        val attempt =
            boardingAttemptRepository.findByIdAndCommuteTripUserId(attemptId, userId)
                ?: throw NotFoundException("boarding attempt $attemptId not found")
        attempt.applyChanges(request)
        return BoardingAttemptResponse.from(attempt)
    }

    @Transactional(readOnly = true)
    fun search(
        userId: Long,
        routeId: Long?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<CommuteTripResponse> {
        if (from != null && to != null && to.isBefore(from)) {
            throw BadRequestException("to must not be before from")
        }
        val spec =
            Specification.allOf(
                listOfNotNull(
                    Specification<CommuteTrip> {
                        root,
                        _,
                        cb,
                        ->
                        cb.equal(root.get<User>("user").get<Long>("id"), userId)
                    },
                    routeId?.let { id ->
                        Specification<CommuteTrip> {
                            root,
                            _,
                            cb,
                            ->
                            cb.equal(root.get<CommuteRoute>("commuteRoute").get<Long>("id"), id)
                        }
                    },
                    from?.let { d ->
                        Specification<CommuteTrip> {
                            root,
                            _,
                            cb,
                            ->
                            cb.greaterThanOrEqualTo(root.get("tripDate"), d)
                        }
                    },
                    to?.let { d ->
                        Specification<CommuteTrip> {
                            root,
                            _,
                            cb,
                            ->
                            cb.lessThanOrEqualTo(root.get("tripDate"), d)
                        }
                    },
                ),
            )
        val trips =
            commuteTripRepository.findAll(
                spec,
                Sort.by(Sort.Order.desc("tripDate"), Sort.Order.desc("id")),
            )
        val attemptsByTrip =
            boardingAttemptRepository
                .findByCommuteTripIdInOrderByRouteLegSeqOrderAsc(trips.map { it.id!! })
                .groupBy { it.commuteTrip.id!! }
        return trips.map { CommuteTripResponse.from(it, attemptsByTrip[it.id].orEmpty()) }
    }

    private fun findTrip(
        userId: Long,
        tripId: Long,
    ): CommuteTrip =
        commuteTripRepository.findByIdAndUserId(tripId, userId)
            ?: throw NotFoundException("commute trip $tripId not found")

    private fun attemptsOf(trip: CommuteTrip): List<BoardingAttempt> =
        boardingAttemptRepository.findByCommuteTripIdInOrderByRouteLegSeqOrderAsc(listOf(trip.id!!))

    private fun transitLegOf(
        trip: CommuteTrip,
        routeLegId: Long,
    ): RouteLeg {
        val leg =
            routeLegRepository
                .findByIdOrNull(routeLegId)
                ?.takeIf { it.commuteRoute.id == trip.commuteRoute.id }
                ?: throw BadRequestException("routeLegId $routeLegId does not belong to the trip's route")
        if (leg.legType != LegType.TRANSIT) {
            throw BadRequestException("routeLegId $routeLegId is not a TRANSIT leg")
        }
        return leg
    }

    private fun BoardingAttempt.applyChanges(changes: BoardingAttemptChanges) {
        changes.arrivedAtStopAt?.let { arrivedAtStopAt = it }
        changes.vehicleScheduledOrPredictedAt?.let { vehicleScheduledOrPredictedAt = it }
        changes.vehicleActualDepartureAt?.let { vehicleActualDepartureAt = it }
        changes.alightedAt?.let { alightedAt = it }
        changes.result?.let { result = it }
        changes.notes?.let { notes = it }
        requireOrdered(vehicleActualDepartureAt, alightedAt, "alightedAt must not be before vehicleActualDepartureAt")
    }

    private fun requireOrdered(
        earlier: Instant?,
        later: Instant?,
        message: String,
    ) {
        if (earlier != null && later != null && later.isBefore(earlier)) {
            throw BadRequestException(message)
        }
    }
}
