package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GpsTraceRepository : JpaRepository<GpsTrace, Long> {
    /** trip 상세의 GPS 트랙 (#64). `(user_id, recorded_at)`이 유일하므로 id는 순서를 고정하는 보조 키다. */
    fun findByCommuteTripIdOrderByRecordedAtAscIdAsc(commuteTripId: Long): List<GpsTrace>

    /**
     * trip 삭제(#88)에서 그 trip에 묶인 점을 지운다. FK가 `ON DELETE SET NULL`이라 그냥 두면 "상시 수집분"으로
     * 바뀌어 남는다. 반환값은 지운 행 수.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from GpsTrace g where g.commuteTrip.id = :commuteTripId")
    fun deleteByCommuteTripId(
        @Param("commuteTripId") commuteTripId: Long,
    ): Int
}
