package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservationRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 기준 시각 `at`에 시스템이 "다음 차"로 알려 줬을 시각 (#54). 탑승 시도의 `vehicleScheduledOrPredictedAt`
 * 스냅샷을 서버가 채울 때 쓴다.
 *
 * 1. 실시간: `observed_at`이 `[at − 2분, at]`인 가장 최근 관측 묶음에서 `at` 이후(같은 시각 포함) 가장 이른
 *    `predicted_arrival_at`. `at` 뒤의 관측은 그 순간 몰랐던 값이라 쓰지 않는다. 허용 오차는 두지 않는다.
 * 2. 정적 시간표: 구간 방향(`LegDirectionResolver`)으로 `at` 이후 첫 출발. day_type·자정 처리는
 *    [TransitScheduleService.departuresFrom]을 그대로 쓴다.
 * 3. 둘 다 없으면 null.
 *
 * 입력이 같으면 언제 계산해도 결과가 같다(관측은 `at` 이전 것만 보고 시간표는 고정). 그래서 기준 시각이
 * 나중에 생겨 늦게 채워도 "그 순간의 스냅샷"과 같은 값이다.
 */
@Component
class NextVehicleSnapshotResolver(
    private val observationRepository: TransitArrivalObservationRepository,
    private val directionResolver: LegDirectionResolver,
    private val scheduleService: TransitScheduleService,
) {
    fun resolve(
        lineId: Long,
        boardStopId: Long,
        alightStopId: Long?,
        at: Instant,
    ): Instant? {
        observationRepository
            .findEarliestPredictionInLatestBatch(lineId, boardStopId, at.minus(OBSERVATION_MAX_AGE), at)
            ?.let { return it }
        val direction = directionResolver.resolve(lineId, boardStopId, alightStopId) ?: return null
        return scheduleService.departuresFrom(lineId, boardStopId, direction, at, 1).firstOrNull()?.departureAt
    }

    companion object {
        /**
         * 이보다 오래된 관측은 그 순간의 예측으로 보지 않는다.
         * 기본 폴링 간격(`wio.polling.interval-ms`, 60초)의 두 배라 한 사이클을 놓쳐도 남는다.
         */
        val OBSERVATION_MAX_AGE: Duration = Duration.ofMinutes(2)
    }
}
