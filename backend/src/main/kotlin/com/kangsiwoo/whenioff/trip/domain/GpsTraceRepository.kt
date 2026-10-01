package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository

interface GpsTraceRepository : JpaRepository<GpsTrace, Long> {
    /** trip 상세의 GPS 트랙 (#64). `(user_id, recorded_at)`이 유일하므로 id는 순서를 고정하는 보조 키다. */
    fun findByCommuteTripIdOrderByRecordedAtAscIdAsc(commuteTripId: Long): List<GpsTrace>
}
