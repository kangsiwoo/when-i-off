package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.external.tago.bus.TagoBusRouteApi
import com.kangsiwoo.whenioff.external.tago.bus.TagoRoute
import com.kangsiwoo.whenioff.external.tago.bus.TagoRouteStop
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

/**
 * 한 번의 `bus-route` 동기화 결과. 같은 도시에 같은 번호의 노선이 여러 개(지선/직행 등)일 수 있어
 * 매칭된 노선을 전부 등록하고 `routeIds`에 나열한다. 카운트는 매칭된 노선 전체의 합계다.
 */
data class BusRouteSyncResult(
    val cityCode: String,
    val routeNo: String,
    val routeIds: List<String>,
    val lines: SyncCounts,
    val stops: SyncCounts,
    val lineStops: SyncCounts,
)

@Service
class TagoMasterSyncService(
    private val routeApi: TagoBusRouteApi,
    private val lineRepository: TransitLineRepository,
    private val stopRepository: TransitStopRepository,
    private val lineStopRepository: TransitLineStopRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    /** 매칭되는 노선이 하나도 없으면 빈 `routeIds`로 돌려준다 (컨트롤러가 404로 바꾼다). */
    fun syncBusRoute(
        cityCode: String,
        routeNo: String,
    ): BusRouteSyncResult {
        val matched = matchRoutes(cityCode, routeNo)
        if (matched.isEmpty()) {
            return BusRouteSyncResult(cityCode, routeNo, emptyList(), EMPTY_COUNTS, EMPTY_COUNTS, EMPTY_COUNTS)
        }
        val routeStops = matched.associateWith { routeApi.getRouteAcctoThrghSttnList(cityCode, it.routeId) }
        return transactionTemplate.execute { persist(cityCode, routeNo, routeStops) }!!
    }

    // TAGO의 노선번호 검색은 부분일치도 돌려주므로(1001을 찾으면 10012도 같이 온다) 번호가 정확히 같은
    // 것만 등록한다. 다만 정확히 같은 것이 하나도 없으면 검색 결과를 그대로 쓴다 — 지자체에 따라
    // routeno에 접미사(1001-1 등)가 붙어 오는 경우가 있어 무조건 0건으로 떨어뜨리지 않는다.
    private fun matchRoutes(
        cityCode: String,
        routeNo: String,
    ): List<TagoRoute> {
        val routes =
            routeApi
                .getRouteNoList(
                    cityCode,
                    routeNo,
                ).filter { it.routeId.isNotBlank() }
                .distinctBy { it.routeId }
        val exact = routes.filter { it.routeNo.trim() == routeNo.trim() }
        return exact.ifEmpty { routes }
    }

    private fun persist(
        cityCode: String,
        routeNo: String,
        routeStops: Map<TagoRoute, List<TagoRouteStop>>,
    ): BusRouteSyncResult {
        var linesCreated = 0
        var linesUpdated = 0
        val linesByRouteId =
            lineRepository
                .findAllByModeAndStdgCd(TransitMode.BUS, cityCode)
                .associateBy { it.externalId }
                .toMutableMap()
        for (route in routeStops.keys) {
            val name = route.routeNo.ifBlank { route.routeId }
            val existing = linesByRouteId[route.routeId]
            if (existing == null) {
                linesByRouteId[route.routeId] =
                    lineRepository.save(
                        TransitLine(
                            mode = TransitMode.BUS,
                            name = name,
                            stdgCd = cityCode,
                            externalId = route.routeId,
                            agency = route.routeTp.ifBlank { null },
                        ),
                    )
                linesCreated++
            } else if (existing.name != name || existing.agency != route.routeTp.ifBlank { null }) {
                existing.name = name
                existing.agency = route.routeTp.ifBlank { null }
                linesUpdated++
            }
        }

        var stopsCreated = 0
        var stopsUpdated = 0
        var stopsSkipped = 0
        val stopsByNodeId =
            stopRepository
                .findAllByModeAndStdgCd(TransitMode.BUS, cityCode)
                .associateBy { it.externalId }
                .toMutableMap()
        val allRouteStops = routeStops.values.flatten()
        for (rs in allRouteStops.distinctBy { it.nodeId }) {
            val lat = rs.lat
            val lng = rs.lng
            if (rs.nodeId.isBlank() || lat == null || lng == null) {
                stopsSkipped++
                continue
            }
            val name = rs.nodeNm.ifBlank { rs.nodeId }
            val existing = stopsByNodeId[rs.nodeId]
            if (existing == null) {
                stopsByNodeId[rs.nodeId] =
                    stopRepository.save(
                        TransitStop(
                            mode = TransitMode.BUS,
                            name = name,
                            lat = lat,
                            lng = lng,
                            stdgCd = cityCode,
                            externalId = rs.nodeId,
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
                .findAllByTransitLineIn(linesByRouteId.values)
                .associateBy { Triple(it.transitLine.id, it.directionCode, it.seqNo) }
                .toMutableMap()
        for ((route, stops) in routeStops) {
            val line = linesByRouteId[route.routeId]
            for (rs in stops) {
                val stop = stopsByNodeId[rs.nodeId]
                val seqNo = rs.seqNo
                if (line == null || stop == null || seqNo == null || rs.updownCd.isBlank()) {
                    lineStopsSkipped++
                    continue
                }
                val key = Triple(line.id, rs.updownCd, seqNo)
                val existing = existingLineStops[key]
                if (existing == null) {
                    existingLineStops[key] = lineStopRepository.save(TransitLineStop(line, stop, rs.updownCd, seqNo))
                    lineStopsCreated++
                } else if (existing.stop.id != stop.id) {
                    existing.stop = stop
                    lineStopsUpdated++
                }
            }
        }

        val result =
            BusRouteSyncResult(
                cityCode = cityCode,
                routeNo = routeNo,
                routeIds = routeStops.keys.map { it.routeId },
                lines = SyncCounts(routeStops.size, linesCreated, linesUpdated),
                stops =
                    SyncCounts(
                        allRouteStops.distinctBy { it.nodeId }.size,
                        stopsCreated,
                        stopsUpdated,
                        stopsSkipped,
                    ),
                lineStops = SyncCounts(allRouteStops.size, lineStopsCreated, lineStopsUpdated, lineStopsSkipped),
            )
        log.info { "tago bus route sync $cityCode/$routeNo: $result" }
        return result
    }

    companion object {
        private val EMPTY_COUNTS = SyncCounts(0, 0, 0)
    }
}
