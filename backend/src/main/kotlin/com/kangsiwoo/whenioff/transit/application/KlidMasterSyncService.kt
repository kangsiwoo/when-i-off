package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.external.klid.bus.BusRouteMaster
import com.kangsiwoo.whenioff.external.klid.bus.BusRouteStop
import com.kangsiwoo.whenioff.external.klid.bus.KlidBusApi
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLineStop
import com.kangsiwoo.whenioff.transit.domain.TransitLineStopRepository
import com.kangsiwoo.whenioff.transit.domain.TransitMode
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

data class SyncCounts(
    val fetched: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int = 0,
)

data class BusMasterSyncResult(
    val stdgCd: String,
    val lines: SyncCounts,
    val stops: SyncCounts,
    val lineStops: SyncCounts,
)

@Service
class KlidMasterSyncService(
    private val busApi: KlidBusApi,
    private val lineRepository: TransitLineRepository,
    private val stopRepository: TransitStopRepository,
    private val lineStopRepository: TransitLineStopRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun syncBusMaster(stdgCd: String): BusMasterSyncResult {
        val routes = busApi.mstInfo(stdgCd)
        val routeStops = busApi.psInfo(stdgCd)
        return transactionTemplate.execute { persist(stdgCd, routes, routeStops) }!!
    }

    private fun persist(
        stdgCd: String,
        routes: List<BusRouteMaster>,
        routeStops: List<BusRouteStop>,
    ): BusMasterSyncResult {
        var linesCreated = 0
        var linesUpdated = 0
        val linesByRteId =
            lineRepository.findAllByModeAndStdgCd(TransitMode.BUS, stdgCd).associateBy { it.externalId }.toMutableMap()
        for (route in routes.distinctBy { it.rteId }) {
            if (route.rteId.isBlank()) continue
            val name = route.rteNo.ifBlank { route.rteId }
            val existing = linesByRteId[route.rteId]
            if (existing == null) {
                linesByRteId[route.rteId] =
                    lineRepository.save(
                        TransitLine(
                            mode = TransitMode.BUS,
                            name = name,
                            stdgCd = stdgCd,
                            externalId = route.rteId,
                            agency = route.lclgvNm.ifBlank { null },
                        ),
                    )
                linesCreated++
            } else if (existing.name != name || existing.agency != route.lclgvNm.ifBlank { null }) {
                existing.name = name
                existing.agency = route.lclgvNm.ifBlank { null }
                linesUpdated++
            }
        }

        var stopsCreated = 0
        var stopsUpdated = 0
        var stopsSkipped = 0
        val stopsByBstaId =
            stopRepository.findAllByModeAndStdgCd(TransitMode.BUS, stdgCd).associateBy { it.externalId }.toMutableMap()
        for (rs in routeStops.distinctBy { it.bstaId }) {
            val lat = rs.lat
            val lng = rs.lng
            if (rs.bstaId.isBlank() || lat == null || lng == null) {
                stopsSkipped++
                continue
            }
            val name = rs.bstaNm.ifBlank { rs.bstaId }
            val existing = stopsByBstaId[rs.bstaId]
            if (existing == null) {
                stopsByBstaId[rs.bstaId] =
                    stopRepository.save(
                        TransitStop(
                            mode = TransitMode.BUS,
                            name = name,
                            lat = lat,
                            lng = lng,
                            stdgCd = stdgCd,
                            externalId = rs.bstaId,
                        ),
                    )
                stopsCreated++
            } else if (existing.name != name || existing.lat != lat || existing.lng != lng) {
                existing.name = name
                existing.lat = lat
                existing.lng = lng
                stopsUpdated++
            }
        }

        var lineStopsCreated = 0
        var lineStopsUpdated = 0
        var lineStopsSkipped = 0
        val existingLineStops =
            lineStopRepository
                .findAllByTransitLineIn(linesByRteId.values)
                .associateBy { Triple(it.transitLine.id, it.directionCode, it.seqNo) }
                .toMutableMap()
        for (rs in routeStops) {
            val line = linesByRteId[rs.rteId]
            val stop = stopsByBstaId[rs.bstaId]
            val seqNo = rs.seqNo
            if (line == null || stop == null || seqNo == null || rs.drcGbnCd.isBlank()) {
                lineStopsSkipped++
                continue
            }
            val key = Triple(line.id, rs.drcGbnCd, seqNo)
            val existing = existingLineStops[key]
            if (existing == null) {
                existingLineStops[key] = lineStopRepository.save(TransitLineStop(line, stop, rs.drcGbnCd, seqNo))
                lineStopsCreated++
            } else if (existing.stop.id != stop.id) {
                existing.stop = stop
                lineStopsUpdated++
            }
        }

        val result =
            BusMasterSyncResult(
                stdgCd = stdgCd,
                lines = SyncCounts(routes.size, linesCreated, linesUpdated),
                stops = SyncCounts(routeStops.distinctBy { it.bstaId }.size, stopsCreated, stopsUpdated, stopsSkipped),
                lineStops = SyncCounts(routeStops.size, lineStopsCreated, lineStopsUpdated, lineStopsSkipped),
            )
        log.info { "bus master sync $stdgCd: $result" }
        return result
    }
}
