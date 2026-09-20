package com.kangsiwoo.whenioff.signal.domain

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
@Table(name = "traffic_signal_states")
class TrafficSignalState(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "traffic_signal_id", nullable = false)
    var trafficSignal: TrafficSignal,
    @Column(nullable = false)
    var observedAt: Instant,
    @Column(nullable = false)
    var approachDir: String,
    @Column(nullable = false)
    var signalKind: String,
    @Column(nullable = false)
    var status: String,
    var remainingDs: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
