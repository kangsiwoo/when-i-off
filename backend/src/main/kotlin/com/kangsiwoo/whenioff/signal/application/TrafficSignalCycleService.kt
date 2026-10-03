package com.kangsiwoo.whenioff.signal.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.signal.api.TrafficSignalCycleRequest
import com.kangsiwoo.whenioff.signal.api.TrafficSignalCycleResponse
import com.kangsiwoo.whenioff.signal.domain.SignalCycleRules
import com.kangsiwoo.whenioff.signal.domain.SignalDataSource
import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalCycle
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalCycleRepository
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

/**
 * 교차로별 신호 주기 행 조회와 사용자 관측(`USER_OBSERVED`) 행 교체 (#78).
 *
 * 교차로는 사용자 소유가 아닌 공용 마스터라(다른 `/traffic-signals` API와 같이) 소유자 검사는 없다.
 * 다른 출처(`PUBLIC_API`, `DEFAULT_ASSUMPTION`) 행은 읽기만 하고 건드리지 않는다. 시간대가 그 행들과 겹쳐도
 * 되며, 어느 행을 쓸지는 소비자(analytics)가 출처 우선순위로 고른다 (DATA_MODEL `traffic_signal_cycles`).
 */
@Service
@Transactional
class TrafficSignalCycleService(
    private val trafficSignalRepository: TrafficSignalRepository,
    private val cycleRepository: TrafficSignalCycleRepository,
) {
    @Transactional(readOnly = true)
    fun list(signalId: Long): List<TrafficSignalCycleResponse> =
        sorted(cycleRepository.findByTrafficSignal(find(signalId)))

    fun replaceUserObserved(
        signalId: Long,
        requests: List<TrafficSignalCycleRequest>,
    ): List<TrafficSignalCycleResponse> {
        val signal = find(signalId)
        val rows = validate(requests).map { toEntity(signal, it) }
        cycleRepository.deleteAllInBatch(
            cycleRepository.findByTrafficSignalAndSource(signal, SignalDataSource.USER_OBSERVED),
        )
        cycleRepository.saveAllAndFlush(rows)
        return sorted(cycleRepository.findByTrafficSignal(signal))
    }

    private fun find(signalId: Long): TrafficSignal =
        trafficSignalRepository
            .findById(
                signalId,
            ).orElseThrow { NotFoundException("traffic signal $signalId not found") }

    /** 초 미만을 버린 요청을 돌려준다. 메시지의 `cycles[i]`는 요청 배열의 순서다. */
    private fun validate(requests: List<TrafficSignalCycleRequest>): List<TrafficSignalCycleRequest> {
        val normalized =
            requests.map {
                it.copy(
                    timeBandStart = it.timeBandStart.truncatedTo(ChronoUnit.SECONDS),
                    timeBandEnd = it.timeBandEnd.truncatedTo(ChronoUnit.SECONDS),
                )
            }
        normalized.forEachIndexed { i, c ->
            val at = "cycles[$i]"
            if (c.cycleDurationSec !in SignalCycleRules.MIN_CYCLE_SEC..SignalCycleRules.MAX_CYCLE_SEC) {
                throw BadRequestException(
                    "$at: cycleDurationSec must be between ${SignalCycleRules.MIN_CYCLE_SEC} and " +
                        "${SignalCycleRules.MAX_CYCLE_SEC} seconds, got ${c.cycleDurationSec}",
                )
            }
            if (c.redDurationSec <= 0 || c.redDurationSec >= c.cycleDurationSec) {
                throw BadRequestException(
                    "$at: redDurationSec must be greater than 0 and less than cycleDurationSec " +
                        "(${c.cycleDurationSec}), got ${c.redDurationSec}",
                )
            }
            if (!c.timeBandStart.isBefore(c.timeBandEnd)) {
                throw BadRequestException(
                    "$at: timeBandStart (${c.timeBandStart}) must be before timeBandEnd (${c.timeBandEnd}); " +
                        "a band cannot cross midnight, split it into two bands",
                )
            }
        }
        normalized
            .withIndex()
            .groupBy { it.value.dayType }
            .forEach { (dayType, bands) ->
                bands.sortedBy { it.value.timeBandStart }.zipWithNext().forEach { (a, b) ->
                    if (b.value.timeBandStart.isBefore(a.value.timeBandEnd)) {
                        throw BadRequestException(
                            "cycles[${a.index}] and cycles[${b.index}] overlap on $dayType " +
                                "(${a.value.timeBandStart}-${a.value.timeBandEnd} and " +
                                "${b.value.timeBandStart}-${b.value.timeBandEnd})",
                        )
                    }
                }
            }
        return normalized
    }

    private fun toEntity(
        signal: TrafficSignal,
        c: TrafficSignalCycleRequest,
    ) = TrafficSignalCycle(
        trafficSignal = signal,
        dayType = c.dayType,
        timeBandStart = c.timeBandStart,
        timeBandEnd = c.timeBandEnd,
        redDurationSec = c.redDurationSec,
        cycleDurationSec = c.cycleDurationSec,
        source = SignalDataSource.USER_OBSERVED,
    )

    private fun sorted(rows: List<TrafficSignalCycle>): List<TrafficSignalCycleResponse> =
        rows
            .sortedWith(
                compareBy<TrafficSignalCycle>(
                    { it.dayType.ordinal },
                    { it.timeBandStart },
                    { SignalCycleRules.SOURCE_PRIORITY.indexOf(it.source) },
                    { it.id },
                ),
            ).map(TrafficSignalCycleResponse::from)
}
