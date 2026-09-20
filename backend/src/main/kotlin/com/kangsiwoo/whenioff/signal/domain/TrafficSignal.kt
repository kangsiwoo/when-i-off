package com.kangsiwoo.whenioff.signal.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "traffic_signals")
class TrafficSignal(
    @Column(nullable = false)
    var lat: Double,
    @Column(nullable = false)
    var lng: Double,
    var stdgCd: String? = null,
    var crsrdId: String? = null,
    var name: String? = null,
    var description: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
