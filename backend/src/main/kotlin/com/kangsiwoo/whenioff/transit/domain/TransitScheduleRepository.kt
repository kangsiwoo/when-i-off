package com.kangsiwoo.whenioff.transit.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TransitScheduleRepository : JpaRepository<TransitSchedule, Long>
