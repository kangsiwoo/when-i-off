package com.kangsiwoo.whenioff.route.api

import com.kangsiwoo.whenioff.route.application.RouteLegService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/route-legs")
class RouteLegController(
    private val routeLegService: RouteLegService,
) {
    @PutMapping("/{id}/signal-crossings")
    fun replaceSignalCrossings(
        @PathVariable id: Long,
        @Valid @RequestBody request: ReplaceSignalCrossingsRequest,
    ): RouteLegResponse = routeLegService.replaceSignalCrossings(id, request.crossings)
}
