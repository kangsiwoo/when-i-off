package com.kangsiwoo.whenioff.admin.api

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.transit.application.TagoMasterSyncService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** TAGO(버스) 동기화. `cityCode`/`routeNo`는 TAGO 값 그대로 — KLID `stdgCd`와 코드 체계가 다르다. */
@RestController
@RequestMapping("/api/v1/admin/sync/tago")
class TagoAdminSyncController(
    private val masterSyncService: TagoMasterSyncService,
) {
    @PostMapping("/bus-route")
    fun syncBusRoute(
        @RequestParam cityCode: String,
        @RequestParam routeNo: String,
    ): BusRouteSyncResponse {
        val result =
            masterSyncService.syncBusRoute(
                required(cityCode, "cityCode"),
                required(routeNo, "routeNo"),
            )
        if (result.routeIds.isEmpty()) {
            throw NotFoundException("no TAGO route matched cityCode=$cityCode routeNo=$routeNo")
        }
        return BusRouteSyncResponse(
            cityCode = result.cityCode,
            routeNo = result.routeNo,
            routeIds = result.routeIds,
            lines = SyncCountsResponse.from(result.lines),
            stops = SyncCountsResponse.from(result.stops),
            lineStops = SyncCountsResponse.from(result.lineStops),
        )
    }

    private fun required(
        value: String,
        name: String,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw BadRequestException("$name must not be blank")
        return trimmed
    }
}
