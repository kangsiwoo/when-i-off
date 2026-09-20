package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TransitLineRepository : JpaRepository<TransitLine, Long> {
    fun findAllByModeAndStdgCd(
        mode: TransitMode,
        stdgCd: String,
    ): List<TransitLine>
}
