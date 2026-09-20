package com.kangsiwoo.whenioff.route.domain

import org.springframework.data.jpa.repository.JpaRepository

interface RouteLegSignalCrossingRepository : JpaRepository<RouteLegSignalCrossing, Long> {
    fun findByRouteLegOrderBySeqOrderAsc(routeLeg: RouteLeg): List<RouteLegSignalCrossing>

    fun findByRouteLegInOrderBySeqOrderAsc(routeLegs: Collection<RouteLeg>): List<RouteLegSignalCrossing>
}
