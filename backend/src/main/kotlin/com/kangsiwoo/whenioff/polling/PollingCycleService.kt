package com.kangsiwoo.whenioff.polling

import com.kangsiwoo.whenioff.external.klid.KlidCallCounter
import com.kangsiwoo.whenioff.external.klid.KlidException
import com.kangsiwoo.whenioff.external.klid.KlidNoDataRegistry
import com.kangsiwoo.whenioff.external.tago.TagoCallCounter
import com.kangsiwoo.whenioff.external.tago.TagoException
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
    private val tagoCallCounter: TagoCallCounter,
    private val klidCallCounter: KlidCallCounter,
    private val transactionTemplate: TransactionTemplate,
) {
    private data class BusLegTarget(
        val cityCode: String,
        val lineId: Long,
        val boardStopId: Long,
        val directionCode: String,
    )

    fun runCycle(): PollingCycleResult {
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
                tagoCallsToday = tagoCallCounter.todayCount(),
                klidCallsToday = klidCallCounter.todayCount(),
            )
        log.info { "polling cycle: $result" }
        return result
    }

    private fun collectBusTargets(): List<BusLegTarget> =
        transactionTemplate.execute {
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

    private fun collectSignalStdgCds(): Set<String> =
        transactionTemplate.execute {
            crossingRepository.findAllForActiveRoutes().mapNotNull { it.trafficSignal.stdgCd }.toSet()
        }!!
}
