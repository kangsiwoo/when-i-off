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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "bus_position_observations")
class BusPositionObservation(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transit_line_id", nullable = false)
    var transitLine: TransitLine,
    @Column(nullable = false)
    var vehicleNo: String,
    @Column(nullable = false)
    var observedAt: Instant,
    @Column(nullable = false)
    var lat: Double,
    @Column(nullable = false)
    var lng: Double,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var raw: String,
    var speedKmh: Double? = null,
    var headingDeg: Double? = null,
    var receiveType: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
