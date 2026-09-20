package com.kangsiwoo.whenioff.route.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RouteLegRepository : JpaRepository<RouteLeg, Long> {
    @Query(
        """
        select l from RouteLeg l
        join fetch l.commuteRoute r
        left join fetch l.transitLine
        left join fetch l.boardStop
        left join fetch l.alightStop
        where r.isActive = true and l.legType = :legType
        """,
    )
    fun findActiveByLegType(
        @Param("legType") legType: LegType,
    ): List<RouteLeg>
}
