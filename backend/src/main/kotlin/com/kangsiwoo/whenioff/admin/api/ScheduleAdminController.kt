package com.kangsiwoo.whenioff.admin.api

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.transit.application.TransitScheduleService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** 정적 시간표(GTX 등 실시간 API가 없는 노선) CSV 적재. 배포된 서버에 셸 없이 넣을 수 있어야 해서 API로 둔다. */
@RestController
@RequestMapping("/api/v1/admin/schedules")
class ScheduleAdminController(
    private val transitScheduleService: TransitScheduleService,
) {
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun import(
        @RequestParam file: MultipartFile,
    ): SyncCountsResponse {
        if (file.isEmpty) throw BadRequestException("file must not be empty")
        return SyncCountsResponse.from(transitScheduleService.importCsv(file.bytes.decodeToString()))
    }
}
