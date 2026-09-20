package com.kangsiwoo.whenioff.route.api

import com.kangsiwoo.whenioff.route.application.CommuteRouteService
import com.kangsiwoo.whenioff.route.application.RouteLegService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/commute-routes")
class CommuteRouteController(
    private val commuteRouteService: CommuteRouteService,
    private val routeLegService: RouteLegService,
) {
    @GetMapping
    fun list(): List<CommuteRouteResponse> = commuteRouteService.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateCommuteRouteRequest,
    ): CommuteRouteResponse = commuteRouteService.create(request)

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: Long,
    ): CommuteRouteDetailResponse = commuteRouteService.detail(id)

    @PutMapping("/{id}/legs")
    fun replaceLegs(
        @PathVariable id: Long,
        @Valid @RequestBody request: ReplaceRouteLegsRequest,
    ): CommuteRouteDetailResponse = routeLegService.replaceLegs(id, request.legs)
}
