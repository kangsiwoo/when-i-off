package com.kangsiwoo.whenioff.route.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface RouteLegSignalCrossingRepository : JpaRepository<RouteLegSignalCrossing, Long> {
    @Query(
        """
        select c from RouteLegSignalCrossing c
        join fetch c.trafficSignal
        where c.routeLeg.commuteRoute.isActive = true
        """,
    )
    fun findAllForActiveRoutes(): List<RouteLegSignalCrossing>
}
