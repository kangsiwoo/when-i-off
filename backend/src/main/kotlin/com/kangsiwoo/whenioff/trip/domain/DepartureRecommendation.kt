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
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
