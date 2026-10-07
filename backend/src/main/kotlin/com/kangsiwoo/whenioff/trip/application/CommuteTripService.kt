package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.ConflictException
import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.CommuteRouteRepository
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.transit.application.NextVehicleSnapshotResolver
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
import com.kangsiwoo.whenioff.trip.domain.GpsTraceRepository
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
    private val gpsTraceRepository: GpsTraceRepository,
    private val nextVehicleSnapshotResolver: NextVehicleSnapshotResolver,
    private val tripRecommendations: TripRecommendations,
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
                return CreateTripResult(responseOf(existing, attemptsOf(existing)), created = false)
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
        return CreateTripResult(responseOf(trip, emptyList()), created = true)
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
        return responseOf(trip, attempts)
    }

    /**
     * 잘못 시작된 기록을 지운다 (#88). 앱의 "기록 취소"가 outbox로 보낸다.
     *
     * 탑승 시도·도보 구간·추천 평가는 FK `ON DELETE CASCADE`로 함께 지워진다(파생값이거나 trip 없이는 뜻이 없다).
     * GPS 포인트는 FK가 `ON DELETE SET NULL`이라 그냥 두면 "상시 수집분"으로 남으므로 먼저 지운다 — 사용자가 버린
     * 기록의 위치를 다른 이름으로 남기지 않는다.
     *
     * 도착이 기록된 trip도 지울 수 있다. 사용자가 명시적으로 "이 기록은 틀렸다"고 판단한 것이라, 구간 교체·보관
     * 정책 같은 부수 효과로 지우지 않는다는 원칙과 부딪치지 않는다. 없거나 남의 trip이면 404 —
     * 이미 지운 trip을 다시 지우는 재전송도 404이고, 클라이언트(outbox)는 그것을 성공으로 본다.
     */
    fun delete(
        userId: Long,
        tripId: Long,
    ) {
        val trip = findTrip(userId, tripId)
        gpsTraceRepository.deleteByCommuteTripId(tripId)
        commuteTripRepository.delete(trip)
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
        // 추천도 탑승 시도처럼 trip 수와 무관하게 한 번에 모은다 (#98).
        val recommendationByTrip = tripRecommendations.forTrips(trips)
        return trips.map { CommuteTripResponse.from(it, attemptsByTrip[it.id].orEmpty(), recommendationByTrip[it.id]) }
    }

    private fun responseOf(
        trip: CommuteTrip,
        attempts: List<BoardingAttempt>,
    ) = CommuteTripResponse.from(trip, attempts, tripRecommendations.forTrip(trip))

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
        val legAttempts = legAttemptsWith(this)
        requireAfterEarlierAttempts(legAttempts)
        // 이 시도의 기준 시각이 생겼을 수도, 이 시도의 출발이 다음 시도의 기준 시각이 됐을 수도 있다.
        legAttempts
            .filter { it === this || it.attemptSeq == attemptSeq + 1 }
            .forEach { fillPredictedSnapshot(it, legAttempts) }
    }

    /** 같은 구간의 시도들(`attempt`의 변경분 포함)을 attemptSeq 순으로. */
    private fun legAttemptsWith(attempt: BoardingAttempt): List<BoardingAttempt> =
        boardingAttemptRepository
            .findByCommuteTripIdAndRouteLegIdOrderByAttemptSeqAsc(attempt.commuteTrip.id!!, attempt.routeLeg.id!!)
            .filter { it !== attempt }
            .plus(attempt)
            .sortedBy { it.attemptSeq }

    /**
     * `vehicleScheduledOrPredictedAt`이 비어 있으면 기준 시각에 시스템이 알려 줬을 "다음 차"로 채운다 (#54).
     * 기준 시각은 첫 시도면 `arrivedAtStopAt`, 뒤 시도면 앞 시도의 `vehicleActualDepartureAt`이다.
     * 기준 시각이 없으면 두었다가 그 값이 들어오는 upsert/PATCH에서 채운다.
     *
     * 이미 값이 있으면(앱이 보냈거나 서버가 채웠거나) 건드리지 않는다 — 그 순간의 스냅샷이라서다.
     */
    private fun fillPredictedSnapshot(
        attempt: BoardingAttempt,
        legAttempts: List<BoardingAttempt>,
    ) {
        if (attempt.vehicleScheduledOrPredictedAt != null) return
        val reference =
            if (attempt.attemptSeq == 1) {
                attempt.arrivedAtStopAt
            } else {
                legAttempts.find { it.attemptSeq == attempt.attemptSeq - 1 }?.vehicleActualDepartureAt
            } ?: return
        val leg = attempt.routeLeg
        val lineId = leg.transitLine?.id ?: return
        val boardStopId = leg.boardStop?.id ?: return
        attempt.vehicleScheduledOrPredictedAt =
            nextVehicleSnapshotResolver.resolve(lineId, boardStopId, leg.alightStop?.id, reference)
    }

    /**
     * 같은 구간에서 뒤 시도(다음 차)는 앞 시도의 차가 떠난 뒤에 떠나야 한다 (#38).
     * 어긋나면 "놓친 차 → 탄 차" 순서가 뒤집혀 시도별 예측 오차와 차내 시간이 엉뚱한 차에 붙는다.
     * 값이 있는 쪽만 검사한다.
     *
     * 뒤 시도의 `arrivedAtStopAt`은 검사하지 않는다 (#42). 정류장에 처음 도착한 시각을 그대로 실어 보내는
     * 것은 자연스러운 값이고, 도보 구간은 첫 시도의 도착만 쓰므로 막아서 지키는 데이터가 없다.
     */
    private fun requireAfterEarlierAttempts(legAttempts: List<BoardingAttempt>) {
        for ((i, earlier) in legAttempts.withIndex()) {
            val departed = earlier.vehicleActualDepartureAt ?: continue
            for (later in legAttempts.drop(i + 1)) {
                val suffix =
                    "of attempt ${later.attemptSeq} must not be before vehicleActualDepartureAt of attempt " +
                        "${earlier.attemptSeq}"
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
