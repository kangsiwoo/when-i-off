package com.kangsiwoo.whenioff.trip.domain

import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 조회는 언제나 **최신 한 건**만 본다. `departure_recommendations`는 과거 추천을 지우지 않고
 * 누적하므로(DATA_MODEL) 같은 (경로, 목표 시각)에 행이 여러 개 있을 수 있다.
 *
 * 정렬은 `computedAt DESC, id DESC`다. `computed_at` 기본값이 `now()`(트랜잭션 시각)라
 * 한 트랜잭션에서 들어간 행들은 값이 같을 수 있어서, 적재하는 쪽(analytics
 * `io/recommendations.py`)과 같은 기준인 id로 동점을 깬다.
 */
interface DepartureRecommendationRepository : JpaRepository<DepartureRecommendation, Long> {
    fun findFirstByCommuteRouteAndTargetArrivalAtOrderByComputedAtDescIdDesc(
        commuteRoute: CommuteRoute,
        targetArrivalAt: Instant,
    ): DepartureRecommendation?

    fun findFirstByCommuteRouteOrderByComputedAtDescIdDesc(commuteRoute: CommuteRoute): DepartureRecommendation?
}
