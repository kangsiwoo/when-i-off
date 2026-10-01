package com.kangsiwoo.whenioff.route.domain

import com.kangsiwoo.whenioff.common.domain.DayType
import com.kangsiwoo.whenioff.user.domain.User
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
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalTime

@Entity
@Table(name = "commute_routes")
class CommuteRoute(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var name: String,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var direction: CommuteDirection,
    @Column(nullable = false)
    var originLat: Double,
    @Column(nullable = false)
    var originLng: Double,
    @Column(nullable = false)
    var destinationLat: Double,
    @Column(nullable = false)
    var destinationLng: Double,
    @Column(nullable = false)
    var isActive: Boolean = true,
    /** 일괄 추천(#68)의 기본 목표 도착 시각. KST 벽시계, null이면 일괄 추천에서 빠진다. */
    @Column(name = "default_target_arrival_time")
    var defaultTargetArrivalTime: LocalTime? = null,
    /**
     * 그날의 day_type이 이 집합에 있을 때만 일괄 추천한다. PostgreSQL `day_type[]`, 비어 있을 수 없다.
     * Hibernate는 enum 배열을 `varchar[]`로 바인딩하므로 쓸 때 `day_type[]`로 캐스트한다 (읽기는 그대로 된다).
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @ColumnTransformer(write = "?::day_type[]")
    @Column(name = "default_target_day_types", nullable = false, columnDefinition = "day_type[]")
    var defaultTargetDayTypes: Array<DayType> = arrayOf(DayType.WEEKDAY),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
