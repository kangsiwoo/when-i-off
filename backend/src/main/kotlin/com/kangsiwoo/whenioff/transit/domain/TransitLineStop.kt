package com.kangsiwoo.whenioff.transit.domain

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
@Table(name = "transit_line_stops")
class TransitLineStop(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transit_line_id", nullable = false)
    var transitLine: TransitLine,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stop_id", nullable = false)
    var stop: TransitStop,
    @Column(nullable = false)
    var directionCode: String,
    @Column(nullable = false)
    var seqNo: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
