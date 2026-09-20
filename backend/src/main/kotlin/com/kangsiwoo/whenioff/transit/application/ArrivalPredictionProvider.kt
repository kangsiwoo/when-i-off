package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import java.time.Instant

data class Prediction(
    /** 차량 식별자. 소스가 주지 않으면 null (TAGO는 차량유형만 주고 차량 번호를 주지 않는다). */
    val vehicleNo: String?,
    val predictedArrivalAt: Instant,
    val observedAt: Instant,
)

interface ArrivalPredictionProvider {
    fun predict(
        line: TransitLine,
        boardStop: TransitStop,
        directionCode: String,
    ): List<Prediction>
}
