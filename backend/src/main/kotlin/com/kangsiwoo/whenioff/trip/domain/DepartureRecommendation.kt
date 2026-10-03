package com.kangsiwoo.whenioff.trip.domain

import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.user.domain.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "departure_recommendations")
class DepartureRecommendation(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commute_route_id", nullable = false)
    var commuteRoute: CommuteRoute,
    @Column(nullable = false)
    var targetDate: LocalDate,
    @Column(nullable = false)
    var targetArrivalAt: Instant,
    @Column(nullable = false)
    var recommendedLeaveHomeAt: Instant,
    @Column(nullable = false)
    var catchProbability: Double,
    @Column(nullable = false)
    var bufferSeconds: Int,
    @Column(nullable = false)
    var modelVersion: String,
    @Column(nullable = false)
    var computedAt: Instant = Instant.now(),
    /**
     * 고른 차량들의 예측 오차·차내 시간 입력 표본 수 중 최솟값 (V8, #86). 0이면 어느 입력이 콜드스타트 기본값이다.
     * V8 이전 행과 TRANSIT 구간이 없는 경로는 null.
     */
    @Column
    var minTransitSampleCount: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
