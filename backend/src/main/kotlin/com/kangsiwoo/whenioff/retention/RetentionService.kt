package com.kangsiwoo.whenioff.retention

import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.ops.application.BatchRunTracker
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val log = KotlinLogging.logger {}

/** 규칙 하나의 결과. dry-run이면 [rows]는 지울 행 수이고 [batches]는 0이다. */
data class RetentionRuleResult(
    val table: String,
    val rule: String,
    val cutoff: Instant,
    val rows: Long,
    val batches: Int,
)

data class RetentionResult(
    val ran: Boolean,
    val dryRun: Boolean,
    val rules: List<RetentionRuleResult>,
) {
    fun rows(table: String): Long = rules.filter { it.table == table }.sumOf { it.rows }
}

/**
 * 오래된 원본 데이터를 지운다 (#74). 규칙은 DATA_MODEL "보관 정책"이 진실이다.
 *
 * 대량 삭제는 `DELETE ... WHERE id IN (SELECT id ... LIMIT n)`을 반복한다. 트랜잭션 없이 JdbcTemplate로
 * 부르므로 문 하나가 곧 커밋 하나다 — 행 잠금이 배치 하나만큼만 잡히고, 중간에 실패해도 그때까지 지운 것은
 * 남는다(다음 실행이 나머지를 이어서 지운다).
 */
@Service
class RetentionService(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: WioProperties,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    private val batchRunTracker: BatchRunTracker,
) {
    private data class Rule(
        val table: String,
        val name: String,
        val days: Long,
        /** `id`만 고르는 SELECT. 파라미터는 cutoff 하나(`?`). */
        val selectIds: String,
    )

    private val config get() = properties.retention

    private fun rules(): List<Rule> =
        listOf(
            // trip의 시각은 집을 나선 때(없으면 trip을 만든 때)다. 같은 trip의 점은 한꺼번에 지워져 반쯤
            // 남은 트랙이 생기지 않는다.
            Rule(
                GPS_TRACES,
                "derived-trip",
                config.gpsDays,
                """
                SELECT g.id FROM gps_traces g JOIN commute_trips t ON t.id = g.commute_trip_id
                WHERE COALESCE(t.left_home_at, t.created_at) < ?
                  AND EXISTS (SELECT 1 FROM walking_segments w WHERE w.commute_trip_id = t.id)
                """.trimIndent(),
            ),
            // 파생되지 않은 trip(사건 누락 등)의 점은 나중에 파생할 수 있게 남기되, 영원히 두지는 않는다.
            Rule(
                GPS_TRACES,
                "underived-trip",
                config.gpsUnderivedDays,
                """
                SELECT g.id FROM gps_traces g JOIN commute_trips t ON t.id = g.commute_trip_id
                WHERE COALESCE(t.left_home_at, t.created_at) < ?
                  AND NOT EXISTS (SELECT 1 FROM walking_segments w WHERE w.commute_trip_id = t.id)
                """.trimIndent(),
            ),
            Rule(
                GPS_TRACES,
                "no-trip",
                config.gpsDays,
                "SELECT id FROM gps_traces WHERE commute_trip_id IS NULL AND recorded_at < ?",
            ),
            Rule(
                ARRIVAL_OBSERVATIONS,
                "observed",
                config.observationDays,
                "SELECT id FROM transit_arrival_observations WHERE observed_at < ?",
            ),
            Rule(
                SIGNAL_STATES,
                "observed",
                config.signalStateDays,
                "SELECT id FROM traffic_signal_states WHERE observed_at < ?",
            ),
        )

    fun run(): RetentionResult {
        if (!config.enabled) {
            log.debug { "retention disabled; nothing to do" }
            return RetentionResult(ran = false, dryRun = config.dryRun, rules = emptyList())
        }
        val now = clock.instant()
        val results =
            try {
                rules().map { apply(it, now.minus(Duration.ofDays(it.days))) }
            } catch (e: RuntimeException) {
                batchRunTracker.retentionFailed(config.dryRun, e)
                throw e
            }
        val result = RetentionResult(ran = true, dryRun = config.dryRun, rules = results)
        batchRunTracker.retentionSucceeded(config.dryRun, TABLES.associateWith { result.rows(it) })
        val verb = if (config.dryRun) "would delete" else "deleted"
        log.info {
            "retention ${if (config.dryRun) "dry-run" else "run"}: $verb " +
                TABLES.joinToString { "$it ${result.rows(it)}" }
        }
        return result
    }

    private fun apply(
        rule: Rule,
        cutoff: Instant,
    ): RetentionRuleResult {
        val cutoffParam = cutoff.atOffset(ZoneOffset.UTC)
        if (config.dryRun) {
            val count =
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM (${rule.selectIds}) s",
                    Long::class.java,
                    cutoffParam,
                )!!
            log.info {
                "retention dry-run ${rule.table}[${rule.name}] older than ${rule.days}d (< $cutoff): would delete $count"
            }
            return RetentionRuleResult(rule.table, rule.name, cutoff, count, 0)
        }

        val sql = "DELETE FROM ${rule.table} WHERE id IN (${rule.selectIds} LIMIT ?)"
        val counter = meterRegistry.counter(METRIC, "table", rule.table, "rule", rule.name)
        var total = 0L
        var batches = 0
        while (true) {
            val deleted = jdbcTemplate.update(sql, cutoffParam, config.batchSize)
            if (deleted == 0) break
            batches++
            total += deleted
            counter.increment(deleted.toDouble())
            if (deleted < config.batchSize) break
        }
        log.info {
            "retention ${rule.table}[${rule.name}] older than ${rule.days}d (< $cutoff): deleted $total in $batches batch(es)"
        }
        return RetentionRuleResult(rule.table, rule.name, cutoff, total, batches)
    }

    companion object {
        const val METRIC = "wio.retention.deleted"
        const val GPS_TRACES = "gps_traces"
        const val ARRIVAL_OBSERVATIONS = "transit_arrival_observations"
        const val SIGNAL_STATES = "traffic_signal_states"
        val TABLES = listOf(GPS_TRACES, ARRIVAL_OBSERVATIONS, SIGNAL_STATES)
    }
}
