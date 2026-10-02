package com.kangsiwoo.whenioff.external.metrics

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * 외부 API 호출의 기간별 집계 (#76). Micrometer Timer는 프로세스 시작 이후 누적만 주므로 "오늘(KST)"·"최근 1시간"은
 * 여기서 따로 센다.
 *
 *  - 오늘: (소스, op)마다 칸 하나. KST 날짜가 바뀌면 다음 기록/조회 때 비운다
 *  - 최근 1시간: 분 단위 칸을 지금 분 포함 [HOUR_MINUTES]개까지 들고 있다가 합친다. 그래서 창은 분 경계에 맞춰
 *    59~60분이다. 자정을 넘는 창도 그대로 이어진다(오늘 칸과 별개)
 *
 * 메모리에만 있어서 **재시작하면 비어 시작한다** — [startedAt]이 집계 시작 시각이다. 기록 빈도가 낮아(분당 수 회)
 * 잠금 하나로 충분하다.
 */
class ExternalCallStore(
    private val clock: Clock,
    private val zone: ZoneId = KST,
) {
    data class Window(
        val calls: Long,
        val failures: Long,
        val outcomes: Map<CallOutcome, Long>,
        val p50Ms: Long?,
        val p95Ms: Long?,
    ) {
        /** 실패 / 호출. 호출이 없으면 null. */
        val failureRate: Double? get() = if (calls == 0L) null else failures.toDouble() / calls
    }

    data class OpStats(
        val source: ExternalSource,
        val op: String,
        val today: Window,
        val lastHour: Window,
    )

    private class Bucket {
        val outcomes = LongArray(CallOutcome.entries.size)
        val latency = LatencyHistogram()

        fun record(
            ms: Double,
            outcome: CallOutcome,
        ) {
            outcomes[outcome.ordinal]++
            latency.record(ms)
        }

        fun add(other: Bucket) {
            for (i in outcomes.indices) outcomes[i] += other.outcomes[i]
            latency.add(other.latency)
        }

        fun toWindow(): Window {
            val byOutcome = CallOutcome.entries.associateWith { outcomes[it.ordinal] }.filterValues { it > 0 }
            return Window(
                calls = outcomes.sum(),
                failures = byOutcome.filterKeys { it.failure }.values.sum(),
                outcomes = byOutcome,
                p50Ms = latency.percentileMs(0.50),
                p95Ms = latency.percentileMs(0.95),
            )
        }
    }

    private class Series(
        var day: LocalDate,
    ) {
        var today = Bucket()

        /** epoch 분 → 그 분의 칸. */
        val minutes = TreeMap<Long, Bucket>()
    }

    private data class Key(
        val source: ExternalSource,
        val op: String,
    )

    val startedAt: Instant = clock.instant()
    private val series = LinkedHashMap<Key, Series>()

    @Synchronized
    fun record(
        source: ExternalSource,
        op: String,
        duration: Duration,
        outcome: CallOutcome,
    ) {
        val now = clock.instant()
        val s = series.getOrPut(Key(source, op)) { Series(today(now)) }
        roll(s, now)
        val ms = duration.toNanos() / 1_000_000.0
        s.today.record(ms, outcome)
        s.minutes.getOrPut(minuteOf(now)) { Bucket() }.record(ms, outcome)
    }

    /** 오늘(KST) 이 소스의 HTTP 시도 수 — 폴링 결과의 `tagoCallsToday`/`klidCallsToday`. */
    @Synchronized
    fun todayCalls(source: ExternalSource): Long {
        val now = clock.instant()
        return series.entries
            .filter { it.key.source == source }
            .sumOf { (_, s) ->
                roll(s, now)
                s.today.outcomes.sum()
            }
    }

    /** 기록이 있었던 (소스, op)마다 한 줄. 재시작 뒤 아직 부르지 않은 op는 없다. */
    @Synchronized
    fun snapshot(): List<OpStats> {
        val now = clock.instant()
        return series.map { (key, s) ->
            roll(s, now)
            val hour = Bucket()
            s.minutes.values.forEach(hour::add)
            OpStats(key.source, key.op, s.today.toWindow(), hour.toWindow())
        }
    }

    /** KST 날짜가 바뀌었으면 오늘 칸을 비우고, 1시간 밖으로 나간 분 칸을 버린다. */
    private fun roll(
        s: Series,
        now: Instant,
    ) {
        val day = today(now)
        if (day != s.day) {
            s.day = day
            s.today = Bucket()
        }
        s.minutes.headMap(minuteOf(now) - HOUR_MINUTES + 1).clear()
    }

    private fun today(now: Instant): LocalDate = now.atZone(zone).toLocalDate()

    private fun minuteOf(now: Instant): Long = Math.floorDiv(now.epochSecond, 60L)

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        const val HOUR_MINUTES = 60L
    }
}
