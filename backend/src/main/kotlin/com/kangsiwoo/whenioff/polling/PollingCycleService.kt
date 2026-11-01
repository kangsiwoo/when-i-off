package com.kangsiwoo.whenioff.polling

import com.kangsiwoo.whenioff.external.klid.KlidException
import com.kangsiwoo.whenioff.external.klid.KlidNoDataRegistry
import com.kangsiwoo.whenioff.external.metrics.ExternalCallMetrics
import com.kangsiwoo.whenioff.external.metrics.ExternalSource
import com.kangsiwoo.whenioff.external.tago.TagoException
import com.kangsiwoo.whenioff.ops.application.BatchRunTracker
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossingRepository
import com.kangsiwoo.whenioff.signal.application.SignalStateIngestService
import com.kangsiwoo.whenioff.transit.application.ArrivalPredictionProvider
import com.kangsiwoo.whenioff.transit.application.LegDirectionResolver
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

data class PollingCycleResult(
    /** 버스는 TAGO cityCode, 신호는 KLID 법정동 코드 — 코드 체계가 다르다 (ADR 0001). */
    val busCityCodes: Set<String>,
    val legsPredicted: Int,
    val predictions: Int,
    val signalStdgCds: Set<String>,
    /** 확정 NODATA(K3)로 표시돼 이번 사이클에 호출하지 않은 지자체 (#31). */
    val signalSkippedStdgCds: Set<String>,
    val signalStatesInserted: Int,
    val failedCodes: Set<String>,
    val tagoCallsToday: Long,
    val klidCallsToday: Long,
)

@Service
class PollingCycleService(
    private val routeLegRepository: RouteLegRepository,
    private val crossingRepository: RouteLegSignalCrossingRepository,
    private val lineRepository: TransitLineRepository,
    private val stopRepository: TransitStopRepository,
    private val directionResolver: LegDirectionResolver,
    private val predictionProvider: ArrivalPredictionProvider,
    private val signalIngestService: SignalStateIngestService,
    private val noDataRegistry: KlidNoDataRegistry,
    private val callMetrics: ExternalCallMetrics,
    private val batchRunTracker: BatchRunTracker,
    private val transactionTemplate: TransactionTemplate,
) {
    private data class BusLegTarget(
        val cityCode: String,
        val lineId: Long,
        val boardStopId: Long,
        val directionCode: String,
    )

    /** 한 사이클. 결과(또는 예외)를 운영 조회 API용으로 남긴다 (#76). */
    fun runCycle(): PollingCycleResult {
        val result =
            try {
                cycle()
            } catch (e: RuntimeException) {
                batchRunTracker.pollingFailed(e)
                throw e
            }
        batchRunTracker.pollingSucceeded(
            result.legsPredicted,
            result.predictions,
            result.signalStatesInserted,
            result.failedCodes,
        )
        return result
    }

    private fun cycle(): PollingCycleResult {
        val failed = mutableSetOf<String>()
        val targets = collectBusTargets()
        val busCityCodes = targets.map { it.cityCode }.toSet()

        // TAGO 도착예측은 (정류장, 노선)마다 직접 조회하므로 미리 받아 둘 지자체 스냅샷이 없다.
        var legsPredicted = 0
        var predictions = 0
        for ((cityCode, group) in targets.groupBy { it.cityCode }) {
            for (target in group) {
                val line = lineRepository.findById(target.lineId).orElse(null) ?: continue
                val stop = stopRepository.findById(target.boardStopId).orElse(null) ?: continue
                try {
                    predictions += predictionProvider.predict(line, stop, target.directionCode).size
                    legsPredicted++
                } catch (e: TagoException) {
                    // 키/한도 문제면 같은 도시의 나머지 구간도 같은 이유로 실패하므로 호출을 더 쓰지 않는다.
                    log.warn(e) { "prediction failed for $cityCode" }
                    failed += cityCode
                    break
                } catch (e: RuntimeException) {
                    // 그 밖의 예외(저장 실패, 예상 못 한 응답 등)는 이 구간만의 문제일 수 있다. 사이클 전체를
                    // 멈추지 않고 실패로만 남긴 뒤 같은 도시의 다음 구간과 신호 폴링을 계속한다.
                    log.error(
                        e,
                    ) { "prediction failed for line ${target.lineId} stop ${target.boardStopId} in $cityCode" }
                    failed += cityCode
                }
            }
        }

        val signalStdgCds = collectSignalStdgCds()
        val skippedStdgCds = mutableSetOf<String>()
        var statesInserted = 0
        for (stdgCd in signalStdgCds) {
            // 실시간 신호가 제공되지 않는 지자체(K3)는 TTL이 끝날 때까지 호출 자체를 하지 않는다.
            // 매 사이클 K3를 받아 오며 KLID 일 한도만 갉아먹던 것을 막는다 (#31).
            if (noDataRegistry.isMarked(stdgCd)) {
                skippedStdgCds += stdgCd
                continue
            }
            try {
                val ingest = signalIngestService.ingest(stdgCd)
                statesInserted += ingest.statesInserted
                // K3일 때만 표시한다. K0 + 0건은 "지금은 보고가 없다"는 일시적 상태라서, 이것까지
                // 표시하면 실제로 커버되는 지자체의 관측을 TTL 동안 조용히 잃는다.
                if (ingest.noData) {
                    noDataRegistry.mark(stdgCd)
                    log.info { "tl_drct_info NODATA for $stdgCd; skipping it for ${noDataRegistry.ttl}" }
                }
            } catch (e: KlidException) {
                log.warn(e) { "tl_drct_info fetch failed for $stdgCd" }
                failed += stdgCd
            } catch (e: RuntimeException) {
                // 한 지자체의 예상 못 한 실패(저장 실패 등)가 다른 지자체 폴링을 막지 않게 한다.
                log.error(e) { "signal ingest failed for $stdgCd" }
                failed += stdgCd
            }
        }

        val result =
            PollingCycleResult(
                busCityCodes = busCityCodes,
                legsPredicted = legsPredicted,
                predictions = predictions,
                signalStdgCds = signalStdgCds,
                signalSkippedStdgCds = skippedStdgCds,
                signalStatesInserted = statesInserted,
                failedCodes = failed,
                // HTTP 시도 수(재시도 포함, 실패 포함) — 일 한도와 같은 기준 (#76)
                tagoCallsToday = callMetrics.todayCount(ExternalSource.TAGO),
                klidCallsToday = callMetrics.todayCount(ExternalSource.KLID),
            )
        log.info { "polling cycle: $result" }
        return result
    }

    private fun collectBusTargets(): List<BusLegTarget> =
        transactionTemplate
            .execute {
                routeLegRepository.findActiveByLegType(LegType.TRANSIT).mapNotNull { leg ->
                    val line = leg.transitLine ?: return@mapNotNull null
                    val board = leg.boardStop ?: return@mapNotNull null
                    val cityCode = line.stdgCd
                    if (line.mode != TransitMode.BUS || !line.hasRealtimeApi || cityCode == null) return@mapNotNull null
                    // TAGO 조회에는 노선(routeId)과 정류장(nodeId)의 외부 ID가 둘 다 필요하다.
                    if (line.externalId == null || board.externalId == null) return@mapNotNull null
                    val direction =
                        directionResolver.resolve(line.id!!, board.id!!, leg.alightStop?.id) ?: return@mapNotNull null
                    BusLegTarget(cityCode, line.id!!, board.id!!, direction)
                }
            }!!
            // 여러 경로(또는 한 경로의 여러 구간)가 같은 (노선, 승차 정류장)을 타면 TAGO 응답도 같다.
            // 한 번만 조회해야 일 한도를 아끼고 같은 관측이 중복 저장되지 않는다. 방향은 TAGO 조회
            // 파라미터가 아니므로(TagoArrivalPredictionProvider) 키에서 뺀다.
            .distinctBy { Triple(it.cityCode, it.lineId, it.boardStopId) }

    private fun collectSignalStdgCds(): Set<String> =
        transactionTemplate.execute {
            crossingRepository.findAllForActiveRoutes().mapNotNull { it.trafficSignal.stdgCd }.toSet()
        }!!
}
