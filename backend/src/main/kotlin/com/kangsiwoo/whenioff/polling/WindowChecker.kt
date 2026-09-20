package com.kangsiwoo.whenioff.polling

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

class WindowChecker(
    windows: List<String>,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    data class Window(
        val start: LocalTime,
        val end: LocalTime,
    ) {
        fun contains(time: LocalTime): Boolean =
            if (start <= end) {
                time >= start && time <= end
            } else {
                time >= start || time <= end
            }
    }

    val windows: List<Window> =
        windows
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::parse)

    fun isWithin(instant: Instant): Boolean {
        if (windows.isEmpty()) return true
        val minute = instant.atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        return windows.any { it.contains(minute) }
    }

    private fun parse(spec: String): Window {
        val parts = spec.split('-')
        require(parts.size == 2) { "polling window must be HH:mm-HH:mm: '$spec'" }
        return try {
            Window(LocalTime.parse(parts[0].trim(), FORMAT), LocalTime.parse(parts[1].trim(), FORMAT))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("polling window must be HH:mm-HH:mm: '$spec'", e)
        }
    }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }
}
