package com.kangsiwoo.whenioff.ops.api

import com.kangsiwoo.whenioff.ops.application.OpsStatusService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영 조회 (#76). 다른 관리 API처럼 `X-Api-Token`으로 보호된다(`/api/` 아래 전부에 걸린 토큰 필터). Actuator 메트릭 엔드포인트는
 * 인증 없이 열리므로 노출하지 않고 이 API로만 본다.
 */
@RestController
@RequestMapping("/api/v1/admin/ops")
class OpsController(
    private val service: OpsStatusService,
) {
    @GetMapping("/external-apis")
    fun externalApis(): OpsStatusResponse = service.status()
}
