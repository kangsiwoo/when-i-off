package com.kangsiwoo.whenioff.signal.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TrafficSignalStateRepository : JpaRepository<TrafficSignalState, Long>
