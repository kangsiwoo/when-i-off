package com.kangsiwoo.whenioff.ops.api

import com.kangsiwoo.whenioff.external.metrics.CallOutcome
import com.kangsiwoo.whenioff.external.metrics.ExternalCallStore
import com.kangsiwoo.whenioff.ops.application.BatchRunResult
import java.time.Instant

/** `GET /admin/ops/external-apis` (#76). 집계는 메모리에만 있어 [collectingSince](재시작 시각) 이후의 것이다. */
data class OpsStatusResponse(
    val generatedAt: Instant,
    /** 오늘(KST) 집계의 시작 = KST 자정. */
    val todayStart: Instant,
    val collectingSince: Instant,
    val sources: List<ExternalSourceOpsResponse>,
    val polling: PollingStatusResponse,
    val retention: RetentionStatusResponse,
)

data class ExternalSourceOpsResponse(
    /** `tago` | `klid` */
    val source: String,
    /** 서비스 키가 설정돼 있는지. 없으면 호출하지 않으므로 행이 0이다. */
    val configured: Boolean,
    val ops: List<ExternalOpResponse>,
)

data class ExternalOpResponse(
    val op: String,
    val today: CallWindowResponse,
    val lastHour: CallWindowResponse,
    val quota: QuotaResponse,
)

/** HTTP 시도 단위(재시도 포함). 호출이 없으면 `failureRate`·`p50Ms`·`p95Ms`가 빠진다. */
data class CallWindowResponse(
    val calls: Long,
    val failures: Long,
    /** 0~1 */
    val failureRate: Double?,
    val p50Ms: Long?,
    val p95Ms: Long?,
    val outcomes: OutcomeCountsResponse,
) {
    companion object {
        fun from(w: ExternalCallStore.Window) =
            CallWindowResponse(
                w.calls,
                w.failures,
                w.failureRate,
                w.p50Ms,
                w.p95Ms,
                OutcomeCountsResponse.from(w.outcomes),
            )
    }
}

data class OutcomeCountsResponse(
    val success: Long,
    val httpError: Long,
    val apiError: Long,
    val timeout: Long,
    val ioError: Long,
) {
    companion object {
        fun from(m: Map<CallOutcome, Long>) =
            OutcomeCountsResponse(
                success = m[CallOutcome.SUCCESS] ?: 0,
                httpError = m[CallOutcome.HTTP_ERROR] ?: 0,
                apiError = m[CallOutcome.API_ERROR] ?: 0,
                timeout = m[CallOutcome.TIMEOUT] ?: 0,
                ioError = m[CallOutcome.IO_ERROR] ?: 0,
            )
    }
}

/** 오늘(KST) 시도 수 / 일 한도(설정값, op별). */
data class QuotaResponse(
    val dailyLimit: Long,
    val used: Long,
    /** 0~ (1을 넘을 수 있다) */
    val usageRate: Double,
)

data class PollingStatusResponse(
    val enabled: Boolean,
    val intervalMs: Long,
    val windows: List<String>,
    /** 지금(KST)이 폴링 창 안인지. 창 밖이면 켜져 있어도 호출하지 않는다. */
    val withinWindow: Boolean,
    val lastRun: PollingRunResponse?,
)

data class PollingRunResponse(
    val at: Instant,
    val result: BatchRunResult,
    val error: String?,
    val legsPredicted: Int,
    val predictions: Int,
    val signalStatesInserted: Int,
    /** 이번 사이클에 실패한 TAGO cityCode / KLID stdgCd. 결과는 SUCCESS여도 일부가 실패했을 수 있다. */
    val failedCodes: List<String>,
)

data class RetentionStatusResponse(
    val enabled: Boolean,
    val dryRun: Boolean,
    /** Spring 6필드 cron, KST */
    val cron: String,
    /** 켜져 있을 때 다음 실행 시각 */
    val nextRunAt: Instant?,
    val lastRun: RetentionRunResponse?,
)

data class RetentionRunResponse(
    val at: Instant,
    val result: BatchRunResult,
    /** 그 실행이 dry-run이었으면 [rows]는 "지울" 행 수 */
    val dryRun: Boolean,
    val error: String?,
    val rows: List<RetentionTableRowsResponse>,
)

data class RetentionTableRowsResponse(
    val table: String,
    val rows: Long,
)
