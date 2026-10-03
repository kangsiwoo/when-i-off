package com.kangsiwoo.whenioff.signal.domain

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
import java.time.temporal.ChronoUnit

@Entity
@Table(name = "traffic_signal_cycles")
class TrafficSignalCycle(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "traffic_signal_id", nullable = false)
    var trafficSignal: TrafficSignal,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var dayType: DayType,
    @Column(nullable = false)
    var timeBandStart: LocalTime,
    @Column(nullable = false)
    var timeBandEnd: LocalTime,
    @Column(nullable = false)
    var redDurationSec: Int,
    @Column(nullable = false)
    var cycleDurationSec: Int,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var source: SignalDataSource = SignalDataSource.DEFAULT_ASSUMPTION,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // DB(TIMESTAMPTZ) 정밀도에 맞춘다. PUT 응답(방금 저장한 엔티티)과 이후 GET이 같은 값을 준다.
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
}
