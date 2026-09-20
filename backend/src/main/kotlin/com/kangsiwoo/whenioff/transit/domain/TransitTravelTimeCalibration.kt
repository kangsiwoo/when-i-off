package com.kangsiwoo.whenioff.transit.domain

import com.kangsiwoo.whenioff.common.domain.DayType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
import java.time.LocalTime

@Entity
@Table(name = "transit_travel_time_calibration")
class TransitTravelTimeCalibration(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transit_line_id", nullable = false)
    var transitLine: TransitLine,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_stop_id", nullable = false)
    var boardStop: TransitStop,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alight_stop_id", nullable = false)
    var alightStop: TransitStop,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var dayType: DayType,
    @Column(nullable = false)
    var timeBandStart: LocalTime,
    @Column(nullable = false)
    var timeBandEnd: LocalTime,
    @Column(nullable = false)
    var meanSec: Int,
    @Column(nullable = false)
    var stddevSec: Int,
    @Column(nullable = false)
    var sampleCount: Int = 0,
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
