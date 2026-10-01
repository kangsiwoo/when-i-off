package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface TransitArrivalObservationRepository : JpaRepository<TransitArrivalObservation, Long> {
    /**
     * (노선, 정류장)에서 `observed_at`이 `[from, at]`인 관측 중 **가장 최근 묶음**에서, `at` 이후 가장 이른 도착 예정 (#54).
     *
     * 폴러는 한 번 조회한 결과(차량 여러 대)를 같은 `observed_at`으로 적재한다(`TagoArrivalPredictionProvider`).
     * 그래서 "묶음"은 `observed_at`이 같은 행들이다. 최근 묶음에 `at` 이후 예정이 없으면 null —
     * 더 오래된 묶음으로 내려가지 않는다(그 순간 시스템이 알던 최신 예측만 스냅샷으로 쓴다).
     */
    @Query(
        """
        select min(o.predictedArrivalAt) from TransitArrivalObservation o
        where o.transitLine.id = :lineId
          and o.stop.id = :stopId
          and o.predictedArrivalAt >= :at
          and o.observedAt = (
            select max(l.observedAt) from TransitArrivalObservation l
            where l.transitLine.id = :lineId
              and l.stop.id = :stopId
              and l.observedAt >= :from
              and l.observedAt <= :at
          )
        """,
    )
    fun findEarliestPredictionInLatestBatch(
        @Param("lineId") lineId: Long,
        @Param("stopId") stopId: Long,
        @Param("from") from: Instant,
        @Param("at") at: Instant,
    ): Instant?
}
