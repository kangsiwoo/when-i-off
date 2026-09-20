package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TransitLineStopRepository : JpaRepository<TransitLineStop, Long> {
    fun findAllByTransitLineIn(lines: Collection<TransitLine>): List<TransitLineStop>

    @Query(
        """
        select ls from TransitLineStop ls join fetch ls.stop
        where ls.transitLine.id = :lineId and ls.directionCode = :directionCode
        order by ls.seqNo
        """,
    )
    fun findPolyline(
        @Param("lineId") lineId: Long,
        @Param("directionCode") directionCode: String,
    ): List<TransitLineStop>

    @Query(
        """
        select ls from TransitLineStop ls
        where ls.transitLine.id = :lineId and ls.stop.id in :stopIds
        """,
    )
    fun findAllByLineAndStopIds(
        @Param("lineId") lineId: Long,
        @Param("stopIds") stopIds: Collection<Long>,
    ): List<TransitLineStop>
}
