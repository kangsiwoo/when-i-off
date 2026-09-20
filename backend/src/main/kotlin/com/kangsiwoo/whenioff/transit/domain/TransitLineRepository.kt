package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TransitLineRepository : JpaRepository<TransitLine, Long> {
    @Query(
        """
        select l from TransitLine l
        where lower(l.name) like lower(concat('%', :keyword, '%'))
           or lower(l.externalId) like lower(concat('%', :keyword, '%'))
        order by l.name asc, l.id asc
        """,
    )
    fun search(
        keyword: String,
        pageable: Pageable,
    ): List<TransitLine>

    @Query(
        """
        select l from TransitLine l
        where l.mode = :mode
          and (lower(l.name) like lower(concat('%', :keyword, '%'))
           or lower(l.externalId) like lower(concat('%', :keyword, '%')))
        order by l.name asc, l.id asc
        """,
    )
    fun searchByMode(
        mode: TransitMode,
        keyword: String,
        pageable: Pageable,
    ): List<TransitLine>

    fun findAllByModeAndStdgCd(
        mode: TransitMode,
        stdgCd: String,
    ): List<TransitLine>
}
