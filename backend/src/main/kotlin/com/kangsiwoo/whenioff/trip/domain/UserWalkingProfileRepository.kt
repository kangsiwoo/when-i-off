package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository

interface UserWalkingProfileRepository : JpaRepository<UserWalkingProfile, Long> {
    /** 구간 행들. 캘리브레이션 상태 조회(#60)가 경로의 WALK 구간을 한 번에 읽는다. */
    fun findByUserIdAndRouteLegIdIn(
        userId: Long,
        routeLegIds: Collection<Long>,
    ): List<UserWalkingProfile>

    /** 사용자 전역 행(`route_leg_id IS NULL`). `UNIQUE NULLS NOT DISTINCT`라 많아야 하나다. */
    fun findFirstByUserIdAndRouteLegIsNull(userId: Long): UserWalkingProfile?
}
