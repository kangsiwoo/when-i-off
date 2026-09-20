package com.kangsiwoo.whenioff.external.tago.bus

import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.tago.TagoHttpClient

/**
 * TAGO 버스도착정보(`ArvlInfoInqireService`) — 정류소별 특정노선 도착예정정보.
 *
 * 정류장 하나 + 노선 하나 단위로만 조회할 수 있어(`cityCode`+`nodeId`+`routeId`) 호출 수가
 * 등록된 구간 수에 비례한다 (ARCHITECTURE.md "TAGO: 필터가 노선/정류소 단위라는 것과 호출 한도").
 */
class TagoArrivalApi(
    private val client: TagoHttpClient,
    private val endpoint: WioProperties.Endpoint,
) {
    fun getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList(
        cityCode: String,
        nodeId: String,
        routeId: String,
    ): List<TagoArrival> =
        client
            .fetchAll(
                endpoint,
                OP_ARRIVAL_BY_STOP_AND_ROUTE,
                mapOf("cityCode" to cityCode, "nodeId" to nodeId, "routeId" to routeId),
            ).map(TagoArrival::from)

    companion object {
        const val OP_ARRIVAL_BY_STOP_AND_ROUTE = "getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList"
    }
}

data class TagoArrival(
    val nodeId: String,
    val nodeNm: String,
    val routeId: String,
    val routeNo: String,
    val routeTp: String,
    val arrPrevStationCnt: String,
    val vehicleTp: String,
    val arrTime: String,
) {
    /** 도착까지 남은 시간(초). TAGO는 차량 번호를 주지 않고 차량유형(`vehicletp`)만 준다. */
    val arrivalInSeconds: Long? get() = arrTime.trim().toLongOrNull()?.takeIf { it >= 0 }
    val prevStationCount: Int? get() = arrPrevStationCnt.trim().toIntOrNull()

    companion object {
        fun from(item: Map<String, String>) =
            TagoArrival(
                nodeId = item.str("nodeid"),
                nodeNm = item.str("nodenm"),
                routeId = item.str("routeid"),
                routeNo = item.str("routeno"),
                routeTp = item.str("routetp"),
                arrPrevStationCnt = item.str("arrprevstationcnt"),
                vehicleTp = item.str("vehicletp"),
                arrTime = item.str("arrtime"),
            )
    }
}
