package com.kangsiwoo.whenioff.route.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RouteLegRepository : JpaRepository<RouteLeg, Long> {
    fun findByCommuteRouteOrderBySeqOrderAsc(commuteRoute: CommuteRoute): List<RouteLeg>

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

    @Query(
        """
        select l.id from route_legs l
        where l.id in (:ids)
          and (exists (select 1 from boarding_attempts b where b.route_leg_id = l.id)
            or exists (select 1 from walking_segments w where w.route_leg_id = l.id)
            or exists (select 1 from user_walking_profile p where p.route_leg_id = l.id))
        """,
        nativeQuery = true,
    )
    fun findIdsWithMeasurements(
        @Param("ids") ids: Collection<Long>,
    ): List<Long>
}
