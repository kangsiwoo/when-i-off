package com.kangsiwoo.whenioff.trip.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * 추천 성과 평가 (V7, #72). analytics `evaluate` 배치가 (경로, `target_date`, `model_version`)마다 파생해 upsert하는
 * 테이블이라 backend는 읽기만 한다(`@Immutable`). 원본(추천·trip)은 id만 들고 관계로 끌고 오지 않는다 — 화면은
 * 행에 복사된 시각과 차이만 쓴다. 값의 정의는 DATA_MODEL.md·analytics/README.md의 `evaluate`.
 */
@Entity
@Immutable
@Table(name = "recommendation_evaluations")
class RecommendationEvaluation(
    @Id
    val id: Long,
    @Column(nullable = false)
    val commuteRouteId: Long,
    /** KST. 추천의 `target_date` = trip의 `trip_date`. */
    @Column(nullable = false)
    val targetDate: LocalDate,
    @Column(nullable = false)
    val modelVersion: String,
    @Column(nullable = false)
    val departureRecommendationId: Long,
    @Column(nullable = false)
    val commuteTripId: Long,
    @Column(nullable = false)
    val targetArrivalAt: Instant,
    @Column(nullable = false)
    val recommendedLeaveHomeAt: Instant,
    val actualLeftHomeAt: Instant?,
    val actualArrivedAt: Instant?,
    /** 실제 출발 − 추천 출발 (+ = 늦게 나감). */
    val departureDiffSec: Int?,
    /** 실제 도착 − 목표 도착 (+ = 늦음). */
    val arrivalDiffSec: Int?,
    /** 실제 도착 > 목표 도착 (정각은 지각 아님). 도착이 없으면 null. */
    val isLate: Boolean?,
    @Column(nullable = false)
    val allLegsCaught: Boolean,
    @Column(nullable = false)
    val missedCount: Int,
    val avgStopWaitSec: Int?,
    @Column(nullable = false)
    val evaluatedAt: Instant,
)
