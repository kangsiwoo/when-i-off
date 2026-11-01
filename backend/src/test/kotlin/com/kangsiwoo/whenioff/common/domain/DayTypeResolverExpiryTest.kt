package com.kangsiwoo.whenioff.common.domain

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 공휴일 목록 갱신을 잊으면 공휴일이 조용히 평일로 떨어지므로 경고 로그로 알린다 (#100 ④). */
class DayTypeResolverExpiryTest {
    private val logger = LoggerFactory.getLogger(DayTypeResolver::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    private val holidays2026 = setOf(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-25"))

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun `warns at startup when the list ends within 90 days`() {
        DayTypeResolver(holidays2026, LocalDate.parse("2026-10-03"))

        val warnings = warnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("[2027]"), warnings[0])
    }

    @Test
    fun `stays quiet when the list covers the next 90 days`() {
        DayTypeResolver(holidays2026, LocalDate.parse("2026-10-02"))

        assertEquals(emptyList(), warnings())
    }

    @Test
    fun `warns once per uncovered year when resolving, and still maps the weekday`() {
        val resolver = DayTypeResolver(holidays2026, LocalDate.parse("2026-05-01"))

        assertEquals(DayType.WEEKDAY, resolver.resolve(LocalDate.parse("2027-01-01")))
        assertEquals(DayType.SUNDAY_HOLIDAY, resolver.resolve(LocalDate.parse("2027-01-03")))
        resolver.resolve(LocalDate.parse("2026-07-01"))

        val warnings = warnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("2027"), warnings[0])
    }

    @Test
    fun `the bundled list covers the current year`() {
        // 목록 갱신이 밀리면 이 테스트가 해가 바뀌는 날 CI를 깨서 알려 준다.
        assertTrue(LocalDate.now(DayTypeResolver.KST).year in DayTypeResolver().coveredYears)
    }
}
