package com.kangsiwoo.whenioff.route.domain

import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitStop
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

@Entity
@Table(name = "route_legs")
class RouteLeg(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commute_route_id", nullable = false)
    var commuteRoute: CommuteRoute,
    @Column(nullable = false)
    var seqOrder: Int,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var legType: LegType,
    var startLat: Double? = null,
    var startLng: Double? = null,
    var endLat: Double? = null,
    var endLng: Double? = null,
    @Column(name = "planned_distance_m")
    var plannedDistanceM: Double? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transit_line_id")
    var transitLine: TransitLine? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_stop_id")
    var boardStop: TransitStop? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alight_stop_id")
    var alightStop: TransitStop? = null,
    var plannedTravelSec: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
