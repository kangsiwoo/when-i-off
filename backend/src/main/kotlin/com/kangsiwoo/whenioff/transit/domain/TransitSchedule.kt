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
@Table(name = "transit_schedules")
class TransitSchedule(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transit_line_id", nullable = false)
    var transitLine: TransitLine,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stop_id", nullable = false)
    var stop: TransitStop,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var dayType: DayType,
    /** `transit_line_stops.direction_code`와 같은 어휘 (KLID drcGbnCd, GTX는 UP/DN). */
    @Column(nullable = false)
    var directionCode: String,
    @Column(nullable = false)
    var scheduledTime: LocalTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
