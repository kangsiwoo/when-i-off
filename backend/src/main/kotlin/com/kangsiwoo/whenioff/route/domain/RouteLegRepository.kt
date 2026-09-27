package com.kangsiwoo.whenioff.route.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RouteLegRepository : JpaRepository<RouteLeg, Long> {
    fun findByCommuteRouteOrderBySeqOrderAsc(commuteRoute: CommuteRoute): List<RouteLeg>

    /**
     * 경로 상세용. TRANSIT 구간의 노선·정류장을 함께 읽는다 — 응답에 이름·좌표를 넣으므로(#36) 따로 읽으면
     * 구간 수만큼 쿼리가 늘어난다(N+1).
     */
    @Query(
        """
        select l from RouteLeg l
        left join fetch l.transitLine
        left join fetch l.boardStop
        left join fetch l.alightStop
        where l.commuteRoute = :route
        order by l.seqOrder asc
        """,
    )
    fun findWithTransitByCommuteRoute(
        @Param("route") route: CommuteRoute,
    ): List<RouteLeg>

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
