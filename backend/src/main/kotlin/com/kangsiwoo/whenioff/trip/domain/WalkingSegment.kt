package com.kangsiwoo.whenioff.trip.domain

import com.kangsiwoo.whenioff.route.domain.RouteLeg
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
@Table(name = "walking_segments")
class WalkingSegment(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commute_trip_id", nullable = false)
    var commuteTrip: CommuteTrip,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_leg_id", nullable = false)
    var routeLeg: RouteLeg,
    @Column(nullable = false)
    var startedAt: Instant,
    @Column(nullable = false)
    var endedAt: Instant,
    @Column(nullable = false)
    var durationSec: Int,
    @Column(name = "distance_m", nullable = false)
    var distanceM: Double,
    @Column(nullable = false)
    var avgSpeedMps: Double,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
