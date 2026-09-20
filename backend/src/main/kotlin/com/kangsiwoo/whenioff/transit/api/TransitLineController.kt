package com.kangsiwoo.whenioff.transit.api

import com.kangsiwoo.whenioff.transit.application.TransitMasterService
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/transit-lines")
class TransitLineController(
    private val transitMasterService: TransitMasterService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateTransitLineRequest,
    ): TransitLineResponse = transitMasterService.createLine(request)

    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) mode: TransitMode?,
        @RequestParam keyword: String,
    ): List<TransitLineResponse> = transitMasterService.searchLines(mode, keyword)
}
