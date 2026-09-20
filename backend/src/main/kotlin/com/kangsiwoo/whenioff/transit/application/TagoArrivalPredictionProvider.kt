package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.external.tago.bus.TagoArrivalApi
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservation
import com.kangsiwoo.whenioff.transit.domain.TransitArrivalObservationRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * TAGO 버스도착정보 기반 도착예측. TAGO가 정류장 단위 도착예정시간(`arrtime`, 초)을 직접 주므로
 * 예전 KLID 위치 기반 구현과 달리 폴리라인 투영/속도 추정이 없다 (ADR 0001).
 *
 * `directionCode`는 TAGO 조회 파라미터가 아니다 — TAGO는 (정류장, 노선) 쌍으로만 조회하고 그 정류장에
 * 오는 방향의 차량만 돌려주므로, 방향은 이미 어느 정류장을 타는지로 결정된다.
 */
@Service
class TagoArrivalPredictionProvider(
    private val arrivalApi: TagoArrivalApi,
    private val arrivalRepository: TransitArrivalObservationRepository,
    private val clock: Clock,
) : ArrivalPredictionProvider {
    @Transactional
    override fun predict(
        line: TransitLine,
        boardStop: TransitStop,
        directionCode: String,
    ): List<Prediction> {
        val cityCode = line.stdgCd
        val routeId = line.externalId
        val nodeId = boardStop.externalId
        if (line.mode != TransitMode.BUS || !line.hasRealtimeApi) return emptyList()
        if (cityCode == null || routeId == null || nodeId == null) return emptyList()

        val observedAt = clock.instant()
        return arrivalApi
            .getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList(cityCode, nodeId, routeId)
            .mapNotNull { arrival ->
                val seconds = arrival.arrivalInSeconds ?: return@mapNotNull null
                // TAGO는 차량 식별자를 주지 않는다(차량유형 vehicletp만) — vehicleNo는 항상 null이다.
                val prediction = Prediction(null, observedAt.plusSeconds(seconds), observedAt)
                arrivalRepository.save(
                    TransitArrivalObservation(
                        transitLine = line,
                        stop = boardStop,
                        observedAt = prediction.observedAt,
                        predictedArrivalAt = prediction.predictedArrivalAt,
                        source = SOURCE,
                        vehicleNo = prediction.vehicleNo,
                    ),
                )
                prediction
            }
    }

    companion object {
        const val SOURCE = "TAGO_ARVL"
    }
}
