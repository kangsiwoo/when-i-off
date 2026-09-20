package com.kangsiwoo.whenioff.trip.domain

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
@Table(name = "gps_traces")
class GpsTrace(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var recordedAt: Instant,
    @Column(nullable = false)
    var lat: Double,
    @Column(nullable = false)
    var lng: Double,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commute_trip_id")
    var commuteTrip: CommuteTrip? = null,
    var speedMps: Double? = null,
    @Column(name = "accuracy_m")
    var accuracyM: Double? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
