package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.external.klid.bus.BusVehiclePosition
import com.kangsiwoo.whenioff.external.klid.bus.KlidBusApi
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class BusPositionSnapshot(
    val stdgCd: String,
    val fetchedAt: Instant,
    val vehicles: List<BusVehiclePosition>,
)

class BusPositionSnapshotCache(
    private val busApi: KlidBusApi,
    private val clock: Clock,
    private val maxAge: Duration,
) {
    private val snapshots = ConcurrentHashMap<String, BusPositionSnapshot>()

    fun get(stdgCd: String): BusPositionSnapshot {
        val now = clock.instant()
        val cached = snapshots[stdgCd]
        if (cached != null && Duration.between(cached.fetchedAt, now) <= maxAge) return cached
        return refresh(stdgCd)
    }

    fun refresh(stdgCd: String): BusPositionSnapshot {
        val snapshot = BusPositionSnapshot(stdgCd, clock.instant(), busApi.rtmLocInfo(stdgCd))
        snapshots[stdgCd] = snapshot
        return snapshot
    }

    fun clear() = snapshots.clear()
}
