package com.kangsiwoo.whenioff.ops.application

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

enum class BatchRunResult { SUCCESS, FAILURE }

data class PollingRun(
    val at: Instant,
    val result: BatchRunResult,
    val error: String? = null,
    val legsPredicted: Int = 0,
    val predictions: Int = 0,
    val signalStatesInserted: Int = 0,
    val failedCodes: Set<String> = emptySet(),
)

data class RetentionRun(
    val at: Instant,
    val result: BatchRunResult,
    val dryRun: Boolean,
    val error: String? = null,
    /** 테이블 → 지운 행 수(dry-run이면 지울 행 수). */
    val rows: Map<String, Long> = emptyMap(),
)

/**
 * 폴링·보관 배치의 마지막 실행 (#76, 운영 조회 API). 메모리에만 두므로 재시작하면 "아직 실행 안 함"으로 돌아간다.
 */
@Component
class BatchRunTracker(
    private val clock: Clock,
) {
    private val polling = AtomicReference<PollingRun?>()
    private val retention = AtomicReference<RetentionRun?>()

    fun lastPolling(): PollingRun? = polling.get()

    fun lastRetention(): RetentionRun? = retention.get()

    fun pollingSucceeded(
        legsPredicted: Int,
        predictions: Int,
        signalStatesInserted: Int,
        failedCodes: Set<String>,
    ) = polling.set(
        PollingRun(
            clock.instant(),
            BatchRunResult.SUCCESS,
            null,
            legsPredicted,
            predictions,
            signalStatesInserted,
            failedCodes,
        ),
    )

    fun pollingFailed(e: Throwable) = polling.set(PollingRun(clock.instant(), BatchRunResult.FAILURE, summary(e)))

    fun retentionSucceeded(
        dryRun: Boolean,
        rows: Map<String, Long>,
    ) = retention.set(RetentionRun(clock.instant(), BatchRunResult.SUCCESS, dryRun, null, rows))

    fun retentionFailed(
        dryRun: Boolean,
        e: Throwable,
    ) = retention.set(RetentionRun(clock.instant(), BatchRunResult.FAILURE, dryRun, summary(e)))

    private fun summary(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().first().take(200)}"
}
