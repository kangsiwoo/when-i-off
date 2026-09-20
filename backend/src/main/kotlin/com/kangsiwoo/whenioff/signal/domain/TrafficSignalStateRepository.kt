package com.kangsiwoo.whenioff.signal.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface TrafficSignalStateRepository : JpaRepository<TrafficSignalState, Long> {
    @Modifying
    @Query(
        value = """
            insert into traffic_signal_states
                (traffic_signal_id, observed_at, approach_dir, signal_kind, status, remaining_ds)
            values (:signalId, :observedAt, :approachDir, :signalKind, :status, :remainingDs)
            on conflict (traffic_signal_id, observed_at, approach_dir, signal_kind) do nothing
            """,
        nativeQuery = true,
    )
    fun insertIgnoringDuplicate(
        @Param("signalId") signalId: Long,
        @Param("observedAt") observedAt: Instant,
        @Param("approachDir") approachDir: String,
        @Param("signalKind") signalKind: String,
        @Param("status") status: String,
        @Param("remainingDs") remainingDs: Int?,
    ): Int

    fun findAllByTrafficSignalIdOrderByObservedAtDesc(trafficSignalId: Long): List<TrafficSignalState>
}
