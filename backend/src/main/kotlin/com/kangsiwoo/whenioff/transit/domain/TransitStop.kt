package com.kangsiwoo.whenioff.transit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "transit_stops")
class TransitStop(
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var mode: TransitMode,
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false)
    var lat: Double,
    @Column(nullable = false)
    var lng: Double,
    var stdgCd: String? = null,
    var externalId: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
