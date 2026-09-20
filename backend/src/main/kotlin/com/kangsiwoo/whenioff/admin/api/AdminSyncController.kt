package com.kangsiwoo.whenioff.admin.api

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.signal.application.IntersectionSyncService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** KLID(신호등) 동기화. 버스는 TAGO로 옮겨져 [TagoAdminSyncController]가 맡는다 (ADR 0001). */
@RestController
@RequestMapping("/api/v1/admin/sync/klid")
class AdminSyncController(
    private val intersectionSyncService: IntersectionSyncService,
) {
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
