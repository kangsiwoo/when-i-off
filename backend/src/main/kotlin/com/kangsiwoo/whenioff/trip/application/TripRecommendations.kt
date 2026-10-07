package com.kangsiwoo.whenioff.trip.application

import com.kangsiwoo.whenioff.trip.domain.CommuteTrip
import com.kangsiwoo.whenioff.trip.domain.DepartureRecommendation
import com.kangsiwoo.whenioff.trip.domain.DepartureRecommendationRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * trip 응답에 싣는 그날의 추천 한 건 (승차권, #98).
 *
 * 규칙: 그 trip의 (경로, `trip_date`)에서 `modelVersion`마다 마지막 계산(`computed_at DESC, id DESC`)을 고르고, 그 중
 * **가장 늦게 계산된 것**(같으면 뒤 버전). 추천 vs 실제 화면이 지각 판정에 쓰는 기준 추천(desktop
 * `referenceRecommendation`)과 같고, 버전마다 고르는 단계는 추천 이력(#62)·평가(#72)가 버전별로 짝짓는 추천과 같다.
 * 평가 행을 읽지 않는 이유: 평가는 새벽 배치라 오늘 trip에는 아직 없고, 어차피 버전마다 한 행이라 버전을 하나 고르는
 * 규칙이 따로 필요하다. 같은 날 trip이 여럿이면 모두 같은 추천을 받는다.
 */
@Component
class TripRecommendations(
    private val recommendationRepository: DepartureRecommendationRepository,
) {
    /** trip id → 추천. 추천이 없는 trip은 키가 없다. 쿼리는 trip 수와 무관하게 한 번(빈 목록이면 0번). */
    fun forTrips(trips: Collection<CommuteTrip>): Map<Long, DepartureRecommendation> {
        if (trips.isEmpty()) return emptyMap()
        val byDay =
            recommendationRepository
                .findLatestPerVersionForTrips(trips.map { it.id!! })
                .groupBy { Day(it.commuteRoute.id!!, it.targetDate) }
                .mapValues { (_, perVersion) -> reference(perVersion) }
        return trips
            .mapNotNull { trip -> byDay[Day(trip.commuteRoute.id!!, trip.tripDate)]?.let { trip.id!! to it } }
            .toMap()
    }

    fun forTrip(trip: CommuteTrip): DepartureRecommendation? = forTrips(listOf(trip))[trip.id]

    private data class Day(
        val routeId: Long,
        val date: LocalDate,
    )

    companion object {
        /** 버전별 마지막 계산들 중 가장 늦게 계산된 것, 같으면 뒤 버전(`v2` < `v10`). */
        fun reference(perVersion: List<DepartureRecommendation>): DepartureRecommendation =
            perVersion.maxWith(
                compareBy<DepartureRecommendation> { it.computedAt }
                    .thenComparator { a, b -> compareVersions(a.modelVersion, b.modelVersion) },
            )

        private val CHUNK = Regex("\\d+|\\D+")

        /**
         * 숫자는 숫자로, 나머지는 대소문자 무시로 비교하는 버전 순서. desktop `compareVersions`
         * (`localeCompare(…, { numeric: true, sensitivity: "base" })`)와 같은 결과를 내는 범위(`v1`, `v2`, `v10`…)를 맞춘다.
         */
        fun compareVersions(
            a: String,
            b: String,
        ): Int {
            val xs = CHUNK.findAll(a).map { it.value }.toList()
            val ys = CHUNK.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(xs.size, ys.size)) {
                val x = xs[i]
                val y = ys[i]
                val c =
                    if (x[0].isDigit() && y[0].isDigit()) {
                        x.toBigInteger().compareTo(y.toBigInteger())
                    } else {
                        x.compareTo(y, ignoreCase = true)
                    }
                if (c != 0) return c
            }
            return xs.size.compareTo(ys.size)
        }
    }
}
