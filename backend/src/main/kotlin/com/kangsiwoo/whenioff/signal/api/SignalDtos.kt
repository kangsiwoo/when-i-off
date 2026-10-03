package com.kangsiwoo.whenioff.signal.api

import com.kangsiwoo.whenioff.common.domain.DayType
import com.kangsiwoo.whenioff.signal.domain.SignalDataSource
import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalCycle
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalTime

data class CreateTrafficSignalRequest(
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val lat: Double,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val lng: Double,
    val stdgCd: String? = null,
    val crsrdId: String? = null,
    val name: String? = null,
    val description: String? = null,
)

data class TrafficSignalResponse(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val stdgCd: String?,
    val crsrdId: String?,
    val name: String?,
    val description: String?,
    val createdAt: Instant,
    val distanceM: Double? = null,
) {
    companion object {
        fun from(
            signal: TrafficSignal,
            distanceM: Double? = null,
        ) = TrafficSignalResponse(
            id = signal.id!!,
            lat = signal.lat,
            lng = signal.lng,
            stdgCd = signal.stdgCd,
            crsrdId = signal.crsrdId,
            name = signal.name,
            description = signal.description,
            createdAt = signal.createdAt,
            distanceM = distanceM,
        )
    }
}

/**
 * 사용자 관측 주기 한 행 (#78). 시간대는 KST 하루 중 `[timeBandStart, timeBandEnd)`, 자정을 넘지 않는다.
 * 검증(범위·겹침)은 `TrafficSignalCycleService`가 한다 — 오류 메시지에 행 번호를 넣기 위해서다.
 */
data class TrafficSignalCycleRequest(
    val dayType: DayType,
    /** KST `HH:mm[:ss]`. 초 미만은 버린다. */
    val timeBandStart: LocalTime,
    /** KST `HH:mm[:ss]`, 이 시각은 포함하지 않는다. 하루 끝까지는 `23:59:59`. */
    val timeBandEnd: LocalTime,
    val cycleDurationSec: Int,
    val redDurationSec: Int,
)

data class ReplaceTrafficSignalCyclesRequest(
    @field:Size(max = 100) val cycles: List<TrafficSignalCycleRequest>,
)

data class TrafficSignalCycleResponse(
    val id: Long,
    val dayType: DayType,
    val timeBandStart: LocalTime,
    val timeBandEnd: LocalTime,
    val cycleDurationSec: Int,
    val redDurationSec: Int,
    val source: SignalDataSource,
    val createdAt: Instant,
) {
    companion object {
        fun from(cycle: TrafficSignalCycle) =
            TrafficSignalCycleResponse(
                id = cycle.id!!,
                dayType = cycle.dayType,
                timeBandStart = cycle.timeBandStart,
                timeBandEnd = cycle.timeBandEnd,
                cycleDurationSec = cycle.cycleDurationSec,
                redDurationSec = cycle.redDurationSec,
                source = cycle.source,
                createdAt = cycle.createdAt,
            )
    }
}
