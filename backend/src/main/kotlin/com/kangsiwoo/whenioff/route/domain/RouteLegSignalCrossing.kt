package com.kangsiwoo.whenioff.route.domain

import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "route_leg_signal_crossings")
class RouteLegSignalCrossing(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_leg_id", nullable = false)
    var routeLeg: RouteLeg,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "traffic_signal_id", nullable = false)
    var trafficSignal: TrafficSignal,
    @Column(nullable = false)
    var seqOrder: Int,
    @Column(nullable = false)
    var approachDir: String,
    @Column(nullable = false)
    var signalKind: String = "Pd",
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
