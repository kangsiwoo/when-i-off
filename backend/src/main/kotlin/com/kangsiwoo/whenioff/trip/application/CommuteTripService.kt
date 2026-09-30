package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.ConflictException
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

/** `created == false`면 같은 (경로, `leftHomeAt`)의 재전송이라 기존 trip을 돌려준 것이다 (#37). */
data class CreateTripResult(
    val trip: CommuteTripResponse,
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
    ): CreateTripResult {
        val route =
            commuteRouteRepository
                .findByIdOrNull(request.routeId)
                ?.takeIf { it.user.id == userId }
                ?: throw NotFoundException("commute route ${request.routeId} not found")
        // 지하에서 응답을 못 받은 앱이 같은 요청을 다시 보내는 경우다 (#37). 밀리초까지 같은 "집 나섬"의
        // 서로 다른 출근은 없으므로 기존 trip을 돌려준다. leftHomeAt이 없으면 키가 없어 매번 새로 만든다.
        request.leftHomeAt?.let { leftHomeAt ->
            commuteTripRepository.findByCommuteRouteIdAndLeftHomeAt(route.id!!, leftHomeAt)?.let { existing ->
                if (existing.tripDate != request.tripDate) {
                    // 재전송이라면 본문이 같아야 한다. 날짜만 다르면 클라이언트가 날짜를 다르게 계산한 것이다
                    // (KST가 아니라 UTC로 뽑는 등). 조용히 기존 것을 돌려주면 그 버그가 가려진다.
                    throw ConflictException(
                        "a trip for this route already left home at $leftHomeAt with tripDate " +
                            "${existing.tripDate}, not ${request.tripDate}",
                    )
                }
                return CreateTripResult(CommuteTripResponse.from(existing, attemptsOf(existing)), created = false)
            }
        }
        val trip =
            commuteTripRepository.save(
                CommuteTrip(
                    user = userRepository.getReferenceById(userId),
                    commuteRoute = route,
                    tripDate = request.tripDate,
                    leftHomeAt = request.leftHomeAt,
                ),
            )
        return CreateTripResult(CommuteTripResponse.from(trip, emptyList()), created = true)
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
        val attempts = attemptsOf(trip)
        // 탑승 시도가 먼저 기록된 뒤 trip 시각을 고치는 경우. 탑승 시도 쪽에서만 검사하면 요청 순서를
        // 바꿔 같은 모순을 만들 수 있다 (#37).
        attempts.forEach { requireWithinTrip(trip, it) }
        return CommuteTripResponse.from(trip, attempts)
    }

    fun upsertBoardingAttempt(
        userId: Long,
        tripId: Long,
        request: UpsertBoardingAttemptRequest,
    ): UpsertResult {
        val trip = findTrip(userId, tripId)
        // 차 한 대 = 한 행이라 upsert 키는 (trip, 구간, attemptSeq)다 (#38).
        val legAttempts =
            boardingAttemptRepository.findByCommuteTripIdAndRouteLegIdOrderByAttemptSeqAsc(tripId, request.routeLegId)
        val existing = legAttempts.find { it.attemptSeq == request.attemptSeq }
        val nextSeq = (legAttempts.lastOrNull()?.attemptSeq ?: 0) + 1
        if (existing == null && request.attemptSeq > nextSeq) {
            // 건너뛴 번호가 있으면 "그 사이에 놓친 차"가 빠진 기록이 된다. 재전송 순서가 뒤바뀐 앱은 다시 보내면 된다.
            throw BadRequestException(
                "attemptSeq ${request.attemptSeq} skips ahead: next attemptSeq for this leg is $nextSeq",
            )
        }
        val attempt =
            existing?.apply { applyChanges(request) }
                ?: boardingAttemptRepository.save(
                    BoardingAttempt(
                        commuteTrip = trip,
                        routeLeg = transitLegOf(trip, request.routeLegId),
                        attemptSeq = request.attemptSeq,
                    ).apply { applyChanges(request) },
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
                .findByCommuteTripIdInOrderByRouteLegSeqOrderAscAttemptSeqAsc(trips.map { it.id!! })
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
        boardingAttemptRepository.findByCommuteTripIdInOrderByRouteLegSeqOrderAscAttemptSeqAsc(listOf(trip.id!!))

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
        requireWithinTrip(commuteTrip, this)
        requireAfterEarlierAttempts(this)
    }

    /**
     * 같은 구간에서 뒤 시도(다음 차)의 관측 시각은 앞 시도의 차가 떠난 뒤여야 한다 (#38).
     * 어긋나면 "놓친 차 → 탄 차" 순서가 뒤집혀 시도별 예측 오차와 차내 시간이 엉뚱한 차에 붙는다.
     * 값이 있는 쪽만 검사한다. 뒤 시도의 `arrivedAtStopAt`은 보통 비워 둔다 (도보 구간은 첫 시도의 도착을 쓴다).
     */
    private fun requireAfterEarlierAttempts(attempt: BoardingAttempt) {
        val legAttempts =
            boardingAttemptRepository
                .findByCommuteTripIdAndRouteLegIdOrderByAttemptSeqAsc(attempt.commuteTrip.id!!, attempt.routeLeg.id!!)
                .filter { it !== attempt }
                .plus(attempt)
                .sortedBy { it.attemptSeq }
        for ((i, earlier) in legAttempts.withIndex()) {
            val departed = earlier.vehicleActualDepartureAt ?: continue
            for (later in legAttempts.drop(i + 1)) {
                val suffix =
                    "of attempt ${later.attemptSeq} must not be before vehicleActualDepartureAt of attempt " +
                        "${earlier.attemptSeq}"
                requireOrdered(departed, later.arrivedAtStopAt, "arrivedAtStopAt $suffix")
                requireOrdered(departed, later.vehicleActualDepartureAt, "vehicleActualDepartureAt $suffix")
            }
        }
    }

    /**
     * 탑승 시도에서 **관측된** 시각은 모두 집을 나선 뒤, 목적지에 닿기 전이어야 한다 (#37).
     * 어긋나면 도보 구간(집→역, 역→목적지) 시간이 음수가 되어 도보 속도 보정이 오염된다.
     *
     * `vehicleScheduledOrPredictedAt`은 관측이 아니라 시스템이 그 순간 알려준 예측의 스냅샷이라 넣지 않는다.
     */
    private fun requireWithinTrip(
        trip: CommuteTrip,
        attempt: BoardingAttempt,
    ) {
        val observed =
            listOf(
                "arrivedAtStopAt" to attempt.arrivedAtStopAt,
                "vehicleActualDepartureAt" to attempt.vehicleActualDepartureAt,
                "alightedAt" to attempt.alightedAt,
            )
        for ((field, at) in observed) {
            requireOrdered(trip.leftHomeAt, at, "$field must not be before the trip's leftHomeAt")
            requireOrdered(at, trip.arrivedDestinationAt, "$field must not be after the trip's arrivedDestinationAt")
        }
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
