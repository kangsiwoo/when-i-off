package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import java.time.Instant

data class Prediction(
    val vehicleNo: String,
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
