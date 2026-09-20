package com.kangsiwoo.whenioff.polling

import com.kangsiwoo.whenioff.external.klid.KlidCallCounter
import com.kangsiwoo.whenioff.external.klid.KlidException
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossingRepository
import com.kangsiwoo.whenioff.signal.application.SignalStateIngestService
import com.kangsiwoo.whenioff.transit.application.ArrivalPredictionProvider
import com.kangsiwoo.whenioff.transit.application.BusPositionSnapshotCache
import com.kangsiwoo.whenioff.transit.application.LegDirectionResolver
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

data class PollingCycleResult(
    val busStdgCds: Set<String>,
    val legsPredicted: Int,
    val predictions: Int,
    val signalStdgCds: Set<String>,
    val signalStatesInserted: Int,
    val failedStdgCds: Set<String>,
    val klidCallsToday: Long,
)

@Service
class PollingCycleService(
    private val routeLegRepository: RouteLegRepository,
    private val crossingRepository: RouteLegSignalCrossingRepository,
    private val lineRepository: TransitLineRepository,
    private val stopRepository: TransitStopRepository,
    private val directionResolver: LegDirectionResolver,
    private val snapshotCache: BusPositionSnapshotCache,
    private val predictionProvider: ArrivalPredictionProvider,
    private val signalIngestService: SignalStateIngestService,
    private val callCounter: KlidCallCounter,
    private val transactionTemplate: TransactionTemplate,
) {
    private data class BusLegTarget(
        val stdgCd: String,
        val lineId: Long,
        val boardStopId: Long,
        val directionCode: String,
    )

    fun runCycle(): PollingCycleResult {
        val failed = mutableSetOf<String>()
        val targets = collectBusTargets()
        val busStdgCds = targets.map { it.stdgCd }.toSet()

        snapshotCache.clear()
        for (stdgCd in busStdgCds) {
            try {
                snapshotCache.refresh(stdgCd)
            } catch (e: KlidException) {
                log.warn(e) { "rtm_loc_info fetch failed for $stdgCd" }
                failed += stdgCd
            }
        }

        var legsPredicted = 0
        var predictions = 0
        for (target in targets.filter { it.stdgCd !in failed }) {
            val line = lineRepository.findById(target.lineId).orElse(null) ?: continue
            val stop = stopRepository.findById(target.boardStopId).orElse(null) ?: continue
            predictions += predictionProvider.predict(line, stop, target.directionCode).size
            legsPredicted++
        }

        val signalStdgCds = collectSignalStdgCds()
        var statesInserted = 0
        for (stdgCd in signalStdgCds) {
            try {
                statesInserted += signalIngestService.ingest(stdgCd).statesInserted
            } catch (e: KlidException) {
                log.warn(e) { "tl_drct_info fetch failed for $stdgCd" }
                failed += stdgCd
            }
        }

        val result =
            PollingCycleResult(
                busStdgCds = busStdgCds,
                legsPredicted = legsPredicted,
                predictions = predictions,
                signalStdgCds = signalStdgCds,
                signalStatesInserted = statesInserted,
                failedStdgCds = failed,
                klidCallsToday = callCounter.todayCount(),
            )
        log.info { "polling cycle: $result" }
        return result
    }

    private fun collectBusTargets(): List<BusLegTarget> =
        transactionTemplate.execute {
            routeLegRepository.findActiveByLegType(LegType.TRANSIT).mapNotNull { leg ->
                val line = leg.transitLine ?: return@mapNotNull null
                val board = leg.boardStop ?: return@mapNotNull null
                val stdgCd = line.stdgCd
                if (line.mode != TransitMode.BUS || !line.hasRealtimeApi || stdgCd == null || line.externalId == null) {
                    return@mapNotNull null
                }
                val direction =
                    directionResolver.resolve(line.id!!, board.id!!, leg.alightStop?.id) ?: return@mapNotNull null
                BusLegTarget(stdgCd, line.id!!, board.id!!, direction)
            }
        }!!

    private fun collectSignalStdgCds(): Set<String> =
        transactionTemplate.execute {
            crossingRepository.findAllForActiveRoutes().mapNotNull { it.trafficSignal.stdgCd }.toSet()
        }!!
}
