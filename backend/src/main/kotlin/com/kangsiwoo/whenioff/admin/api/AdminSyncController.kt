package com.kangsiwoo.whenioff.admin.api

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.signal.application.IntersectionSyncService
import com.kangsiwoo.whenioff.transit.application.KlidMasterSyncService
import com.kangsiwoo.whenioff.transit.application.SyncCounts
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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

data class BusMasterSyncResponse(
    val stdgCd: String,
    val lines: SyncCountsResponse,
    val stops: SyncCountsResponse,
    val lineStops: SyncCountsResponse,
)

data class IntersectionSyncResponse(
    val stdgCd: String,
    val intersections: SyncCountsResponse,
)

@RestController
@RequestMapping("/api/v1/admin/sync")
class AdminSyncController(
    private val masterSyncService: KlidMasterSyncService,
    private val intersectionSyncService: IntersectionSyncService,
) {
    @PostMapping("/bus-master")
    fun syncBusMaster(
        @RequestParam stdgCd: String,
    ): BusMasterSyncResponse {
        val result = masterSyncService.syncBusMaster(validStdgCd(stdgCd))
        return BusMasterSyncResponse(
            stdgCd = result.stdgCd,
            lines = SyncCountsResponse.from(result.lines),
            stops = SyncCountsResponse.from(result.stops),
            lineStops = SyncCountsResponse.from(result.lineStops),
        )
    }

    @PostMapping("/intersections")
    fun syncIntersections(
        @RequestParam stdgCd: String,
    ): IntersectionSyncResponse {
        val result = intersectionSyncService.syncIntersections(validStdgCd(stdgCd))
        return IntersectionSyncResponse(result.stdgCd, SyncCountsResponse.from(result.intersections))
    }

    private fun validStdgCd(stdgCd: String): String {
        if (!STDG_CD.matches(stdgCd)) throw BadRequestException("stdgCd must be a 10-digit 법정동 시도코드")
        return stdgCd
    }

    companion object {
        private val STDG_CD = Regex("^\\d{10}$")
    }
}
