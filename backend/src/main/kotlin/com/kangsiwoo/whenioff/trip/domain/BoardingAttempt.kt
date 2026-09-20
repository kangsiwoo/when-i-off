package com.kangsiwoo.whenioff.trip.domain

import com.kangsiwoo.whenioff.route.domain.RouteLeg
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
@Table(name = "boarding_attempts")
class BoardingAttempt(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commute_trip_id", nullable = false)
    var commuteTrip: CommuteTrip,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_leg_id", nullable = false)
    var routeLeg: RouteLeg,
    var arrivedAtStopAt: Instant? = null,
    var vehicleScheduledOrPredictedAt: Instant? = null,
    var vehicleActualDepartureAt: Instant? = null,
    var alightedAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var result: BoardingResult = BoardingResult.UNKNOWN,
    var notes: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
