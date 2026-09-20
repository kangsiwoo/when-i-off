package com.kangsiwoo.whenioff.admin.api

import com.kangsiwoo.whenioff.transit.application.SyncCounts

data class SyncCountsResponse(
    val fetched: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
) {
    companion object {
        fun from(counts: SyncCounts) =
            SyncCountsResponse(counts.fetched, counts.created, counts.updated, counts.skipped)
    }
}

data class BusRouteSyncResponse(
    val cityCode: String,
    val routeNo: String,
    val routeIds: List<String>,
    val lines: SyncCountsResponse,
    val stops: SyncCountsResponse,
    val lineStops: SyncCountsResponse,
)

data class IntersectionSyncResponse(
    val stdgCd: String,
    val intersections: SyncCountsResponse,
)
