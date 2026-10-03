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
 * 한 번의 `bus-route` 동기화 결과. 같은 도시에 같은 번호의 노선이 여러 개일 수 있어
 * 매칭된 노선을 전부 등록하고 `routeIds`에 나열한다. 카운트는 매칭된 노선 전체의 합계다.
 * `candidates`는 검색은 됐지만 번호가 정확히 같지 않아 등록하지 않은 노선번호다 (404 안내용).
 */
data class BusRouteSyncResult(
    val cityCode: String,
    val routeNo: String,
    val routeIds: List<String>,
    val lines: SyncCounts,
    val stops: SyncCounts,
    val lineStops: SyncCounts,
    val candidates: List<String> = emptyList(),
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
        val searched = searchRoutes(cityCode, routeNo)
        val matched = searched.filter { it.routeNo.trim() == routeNo.trim() }
        if (matched.isEmpty()) {
            val candidates = searched.map { it.routeNo }.distinct()
            return BusRouteSyncResult(
                cityCode,
                routeNo,
                emptyList(),
                EMPTY_COUNTS,
                EMPTY_COUNTS,
                EMPTY_COUNTS,
                candidates,
            )
        }
        val routeStops = matched.associateWith { routeApi.getRouteAcctoThrghSttnList(cityCode, it.routeId) }
        return transactionTemplate.execute { persist(cityCode, routeNo, routeStops) }!!
    }

    // TAGO의 노선번호 검색은 부분일치(포함)다 — 실 응답에서 `4108`을 찾으면 `M4108`·`M4108(예약)`이,
    // `55`를 찾으면 `8155`·`1551`이 같이 온다(#17). `6002-1`·`1551B`·`(예약)`·`(출근)` 같은 접미사가 붙은
    // 것은 별개 노선(routeId가 다름)이므로 번호가 정확히 같은 것만 등록하고, 없으면 아무것도 등록하지
    // 않는다(예전에는 검색 결과 전체로 대체했는데, 부분일치라 엉뚱한 노선이 등록된다).
    private fun searchRoutes(
        cityCode: String,
        routeNo: String,
    ): List<TagoRoute> =
        routeApi
            .getRouteNoList(cityCode, routeNo)
            .filter { it.routeId.isNotBlank() }
            .distinctBy { it.routeId }

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
            if (rs.nodeId.isBlank() || lat == null || lng == null || rs.isPassThrough) {
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
                // 미정차 지점은 위에서 정류장으로 만들지 않았으므로 stop == null로 여기서 함께 걸러진다.
                if (line == null || stop == null || seqNo == null) {
                    lineStopsSkipped++
                    continue
                }
                val key = Triple(line.id, rs.directionCode, seqNo)
                val existing = existingLineStops[key]
                if (existing == null) {
                    existingLineStops[key] =
                        lineStopRepository.save(TransitLineStop(line, stop, rs.directionCode, seqNo))
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
