package com.kangsiwoo.whenioff.external.klid

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object KlidTime {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parseKstOrNull(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(value.trim(), FORMAT).atZone(KST).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
