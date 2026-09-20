package com.kangsiwoo.whenioff.route.domain

import org.springframework.data.jpa.repository.JpaRepository

interface CommuteRouteRepository : JpaRepository<CommuteRoute, Long> {
    fun findByUserIdOrderByIdAsc(userId: Long): List<CommuteRoute>

    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): CommuteRoute?
}
