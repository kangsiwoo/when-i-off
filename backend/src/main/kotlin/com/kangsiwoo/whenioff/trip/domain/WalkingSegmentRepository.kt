package com.kangsiwoo.whenioff.trip.domain

import org.springframework.data.jpa.repository.JpaRepository

interface WalkingSegmentRepository : JpaRepository<WalkingSegment, Long>
