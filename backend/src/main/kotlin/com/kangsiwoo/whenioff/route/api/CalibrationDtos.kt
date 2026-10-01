package com.kangsiwoo.whenioff.route.api

import com.kangsiwoo.whenioff.common.domain.DayType
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.transit.domain.TransitPredictionCalibration
import com.kangsiwoo.whenioff.transit.domain.TransitTravelTimeCalibration
import com.kangsiwoo.whenioff.trip.domain.UserWalkingProfile
import java.time.Instant
import java.time.LocalTime

/**
 * 경로 하나의 캘리브레이션 상태 (#60, API.md "캘리브레이션 상태 조회"). analytics `calibrate`가 채운 보정
 * 테이블을 읽기만 한다. 어느 단계 값(구간 행 → 상위 그룹 → 기본값)이 추천에 쓰일지는 화면이 `minSamples`로 판단한다.
 */
data class RouteCalibrationResponse(
    val routeId: Long,
    /** 이 수 이상의 샘플이 있는 행만 추천에 쓰인다 — analytics `lookup.MIN_CALIBRATION_SAMPLES`와 같은 값. */
    val minSamples: Int,
    /** 사용자 전역 도보 행(`user_walking_profile.route_leg_id IS NULL`). 구간 행이 부족할 때 쓰인다. 없으면 생략. */
    val globalWalkingProfile: WalkingProfileResponse?,
    /** 경로의 구간들, `seqOrder` 순. */
    val legs: List<LegCalibrationResponse>,
)

data class LegCalibrationResponse(
    val routeLegId: Long,
    val seqOrder: Int,
    val legType: LegType,
    val plannedDistanceM: Double?,
    /** TRANSIT 구간은 차내 시간 기본값(평균 = 이 값, σ = 15%)의 근거다. */
    val plannedTravelSec: Int?,
    /** WALK 구간의 구간 행. 없으면(또는 TRANSIT 구간이면) 생략. */
    val walkingProfile: WalkingProfileResponse?,
    val transitLineId: Long?,
    val transitLineName: String?,
    val boardStopId: Long?,
    val boardStopName: String?,
    val alightStopId: Long?,
    val alightStopName: String?,
    /** 예측 오차: (노선, 승차 정류장)의 모든 day_type × 밴드 행. WALK 구간이거나 행이 없으면 빈 배열. */
    val predictionRows: List<PredictionCalibrationRowResponse>,
    /** 차내 시간: (노선, 승차역, 하차역)의 모든 day_type × 밴드 행. WALK 구간이거나 행이 없으면 빈 배열. */
    val travelTimeRows: List<TravelTimeCalibrationRowResponse>,
)

data class WalkingProfileResponse(
    val avgSpeedMps: Double,
    val stddevSpeedMps: Double,
    val sampleCount: Int,
    val updatedAt: Instant,
) {
    companion object {
        fun from(profile: UserWalkingProfile) =
            WalkingProfileResponse(
                avgSpeedMps = profile.avgSpeedMps,
                stddevSpeedMps = profile.stddevSpeedMps,
                sampleCount = profile.sampleCount,
                updatedAt = profile.updatedAt,
            )
    }
}

/**
 * 예측 오차 한 행. `biasSec`은 실제 − 예측(초). 밴드는 KST 벽시계 30분 `[timeBandStart, timeBandEnd)`이고
 * `"HH:mm"` 문자열이다 — 마지막 밴드의 끝은 DB에 `23:59:59.999999`(TIME은 24:00을 못 담는다)로 있지만
 * 응답에서는 `"24:00"`으로 준다 ([timeBandText]).
 */
data class PredictionCalibrationRowResponse(
    val dayType: DayType,
    val timeBandStart: String,
    val timeBandEnd: String,
    val biasSec: Int,
    val stddevSec: Int,
    val sampleCount: Int,
    val updatedAt: Instant,
) {
    companion object {
        fun from(row: TransitPredictionCalibration) =
            PredictionCalibrationRowResponse(
                dayType = row.dayType,
                timeBandStart = timeBandText(row.timeBandStart),
                timeBandEnd = timeBandText(row.timeBandEnd),
                biasSec = row.biasSec,
                stddevSec = row.stddevSec,
                sampleCount = row.sampleCount,
                updatedAt = row.updatedAt,
            )
    }
}

/** 차내 시간 한 행 (승차역 출발 → 하차역 도착, 초). 밴드 표기는 [PredictionCalibrationRowResponse]와 같다. */
data class TravelTimeCalibrationRowResponse(
    val dayType: DayType,
    val timeBandStart: String,
    val timeBandEnd: String,
    val meanSec: Int,
    val stddevSec: Int,
    val sampleCount: Int,
    val updatedAt: Instant,
) {
    companion object {
        fun from(row: TransitTravelTimeCalibration) =
            TravelTimeCalibrationRowResponse(
                dayType = row.dayType,
                timeBandStart = timeBandText(row.timeBandStart),
                timeBandEnd = timeBandText(row.timeBandEnd),
                meanSec = row.meanSec,
                stddevSec = row.stddevSec,
                sampleCount = row.sampleCount,
                updatedAt = row.updatedAt,
            )
    }
}

/**
 * 밴드 경계를 `"HH:mm"`으로. 밴드는 30분 단위라 초 이하는 없고, 23:59 이후(analytics `LAST_BAND_END` =
 * `time.max`)는 하루의 끝 `"24:00"`으로 쓴다.
 */
fun timeBandText(time: LocalTime): String =
    if (time >= LAST_BAND_END_FLOOR) {
        "24:00"
    } else {
        "%02d:%02d".format(time.hour, time.minute)
    }

private val LAST_BAND_END_FLOOR: LocalTime = LocalTime.of(23, 59)
