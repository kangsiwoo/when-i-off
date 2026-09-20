package com.kangsiwoo.whenioff.trip.domain

import com.kangsiwoo.whenioff.route.domain.RouteLeg
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

@Entity
@Table(name = "user_walking_profile")
class UserWalkingProfile(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_leg_id")
    var routeLeg: RouteLeg? = null,
    @Column(nullable = false)
    var avgSpeedMps: Double,
    @Column(nullable = false)
    var stddevSpeedMps: Double,
    @Column(nullable = false)
    var sampleCount: Int = 0,
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
