package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TransitStopRepository : JpaRepository<TransitStop, Long> {
    fun findAllByModeAndStdgCd(
        mode: TransitMode,
        stdgCd: String,
    ): List<TransitStop>
}
