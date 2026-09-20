package com.kangsiwoo.whenioff.route.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface RouteLegSignalCrossingRepository : JpaRepository<RouteLegSignalCrossing, Long> {
    fun findByRouteLegOrderBySeqOrderAsc(routeLeg: RouteLeg): List<RouteLegSignalCrossing>

    fun findByRouteLegInOrderBySeqOrderAsc(routeLegs: Collection<RouteLeg>): List<RouteLegSignalCrossing>

    @Query(
        """
        select c from RouteLegSignalCrossing c
        join fetch c.trafficSignal
        where c.routeLeg.commuteRoute.isActive = true
        """,
    )
    fun findAllForActiveRoutes(): List<RouteLegSignalCrossing>
}
