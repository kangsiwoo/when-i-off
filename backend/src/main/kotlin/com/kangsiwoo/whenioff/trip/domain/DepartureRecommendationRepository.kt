package com.kangsiwoo.whenioff.trip.domain

import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

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

    /**
     * 기간(KST `target_date`)의 추천 전부, 날짜 → 버전 → 최신순. 화면용 이력(#62)은 여기서 (날짜, 버전)마다
     * 첫 행만 쓴다. 기간은 화면이 날짜 범위로 좁히고 하루 행 수도 작아 전부 읽어 고른다.
     */
    fun findByCommuteRouteAndTargetDateBetweenOrderByTargetDateAscModelVersionAscComputedAtDescIdDesc(
        commuteRoute: CommuteRoute,
        from: LocalDate,
        to: LocalDate,
    ): List<DepartureRecommendation>
}
