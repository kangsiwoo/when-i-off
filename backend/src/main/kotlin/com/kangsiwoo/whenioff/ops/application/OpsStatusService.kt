package com.kangsiwoo.whenioff.ops.application

import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.klid.signal.KlidSignalApi
import com.kangsiwoo.whenioff.external.metrics.ExternalCallStore
import com.kangsiwoo.whenioff.external.metrics.ExternalSource
import com.kangsiwoo.whenioff.external.tago.bus.TagoArrivalApi
import com.kangsiwoo.whenioff.external.tago.bus.TagoBusRouteApi
import com.kangsiwoo.whenioff.ops.api.CallWindowResponse
import com.kangsiwoo.whenioff.ops.api.ExternalOpResponse
import com.kangsiwoo.whenioff.ops.api.ExternalSourceOpsResponse
import com.kangsiwoo.whenioff.ops.api.OpsStatusResponse
import com.kangsiwoo.whenioff.ops.api.PollingRunResponse
import com.kangsiwoo.whenioff.ops.api.PollingStatusResponse
import com.kangsiwoo.whenioff.ops.api.QuotaResponse
import com.kangsiwoo.whenioff.ops.api.RetentionRunResponse
import com.kangsiwoo.whenioff.ops.api.RetentionStatusResponse
import com.kangsiwoo.whenioff.ops.api.RetentionTableRowsResponse
import com.kangsiwoo.whenioff.polling.WindowChecker
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import java.time.Clock

/** 운영 조회 (#76): 외부 API 호출 집계 + 폴링·보관 배치 상태. */
@Service
class OpsStatusService(
    private val store: ExternalCallStore,
    private val batchRunTracker: BatchRunTracker,
    private val properties: WioProperties,
    private val clock: Clock,
) {
    fun status(): OpsStatusResponse {
        val now = clock.instant()
        val stats = store.snapshot().groupBy { it.source }
        val sources =
            ExternalSource.entries.map { source ->
                val seen = stats[source].orEmpty().associateBy { it.op }
                // 이 앱이 부르는 op는 호출 전에도 0으로 보이게 하고, 그 밖에 기록된 op도 뒤에 붙인다.
                val ops = (KNOWN_OPS.getValue(source) + seen.keys).distinct()
                ExternalSourceOpsResponse(
                    source = source.tag,
                    configured = configured(source),
                    ops =
                        ops.map { op ->
                            val s = seen[op]
                            val today = s?.today ?: EMPTY
                            val limit = dailyLimit(source, op)
                            ExternalOpResponse(
                                op = op,
                                today = CallWindowResponse.from(today),
                                lastHour = CallWindowResponse.from(s?.lastHour ?: EMPTY),
                                quota =
                                    QuotaResponse(
                                        limit,
                                        today.calls,
                                        if (limit >
                                            0
                                        ) {
                                            today.calls.toDouble() / limit
                                        } else {
                                            0.0
                                        },
                                    ),
                            )
                        },
                )
            }
        return OpsStatusResponse(
            generatedAt = now,
            todayStart =
                now
                    .atZone(ExternalCallStore.KST)
                    .toLocalDate()
                    .atStartOfDay(ExternalCallStore.KST)
                    .toInstant(),
            collectingSince = store.startedAt,
            sources = sources,
            polling = polling(),
            retention = retention(),
        )
    }

    private fun polling(): PollingStatusResponse {
        val p = properties.polling
        val run = batchRunTracker.lastPolling()
        val windows = WindowChecker(p.windows)
        return PollingStatusResponse(
            enabled = p.enabled,
            intervalMs = p.intervalMs,
            windows = windows.windows.map { "${it.start}-${it.end}" },
            withinWindow = windows.isWithin(clock.instant()),
            lastRun =
                run?.let {
                    PollingRunResponse(
                        at = it.at,
                        result = it.result,
                        error = it.error,
                        legsPredicted = it.legsPredicted,
                        predictions = it.predictions,
                        signalStatesInserted = it.signalStatesInserted,
                        failedCodes = it.failedCodes.sorted(),
                    )
                },
        )
    }

    private fun retention(): RetentionStatusResponse {
        val r = properties.retention
        val run = batchRunTracker.lastRetention()
        val next =
            if (r.enabled) {
                CronExpression
                    .parse(r.cron)
                    .next(clock.instant().atZone(ExternalCallStore.KST))
                    ?.toInstant()
            } else {
                null
            }
        return RetentionStatusResponse(
            enabled = r.enabled,
            dryRun = r.dryRun,
            cron = r.cron,
            nextRunAt = next,
            lastRun =
                run?.let {
                    RetentionRunResponse(
                        at = it.at,
                        result = it.result,
                        dryRun = it.dryRun,
                        error = it.error,
                        rows = it.rows.map { (table, rows) -> RetentionTableRowsResponse(table, rows) },
                    )
                },
        )
    }

    private fun configured(source: ExternalSource): Boolean =
        when (source) {
            ExternalSource.TAGO -> properties.tago.serviceKey.isNotBlank()
            ExternalSource.KLID ->
                properties.klid.signal.serviceKey
                    .isNotBlank()
        }

    private fun dailyLimit(
        source: ExternalSource,
        op: String,
    ): Long =
        when (source) {
            ExternalSource.TAGO -> properties.tago.dailyLimitOf(op)
            ExternalSource.KLID -> properties.klid.dailyLimitOf(op)
        }

    companion object {
        private val EMPTY = ExternalCallStore.Window(0, 0, emptyMap(), null, null)

        val KNOWN_OPS: Map<ExternalSource, List<String>> =
            mapOf(
                ExternalSource.TAGO to
                    listOf(
                        TagoArrivalApi.OP_ARRIVAL_BY_STOP_AND_ROUTE,
                        TagoBusRouteApi.OP_ROUTE_NO_LIST,
                        TagoBusRouteApi.OP_ROUTE_STOP_LIST,
                    ),
                ExternalSource.KLID to listOf(KlidSignalApi.OP_TL_DRCT_INFO, KlidSignalApi.OP_CRSRD_MAP_INFO),
            )
    }
}
