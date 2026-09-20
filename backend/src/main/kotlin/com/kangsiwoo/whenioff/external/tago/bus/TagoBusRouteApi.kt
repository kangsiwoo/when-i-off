package com.kangsiwoo.whenioff.external.tago.bus

import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.tago.TagoHttpClient

/**
 * TAGO 버스노선정보(`BusRouteInfoInqireService`).
 *
 * KLID `rte`와 달리 지자체 전체를 한 번에 받는 오퍼레이션이 없어, 노선번호로 검색해 `routeId`를 얻은 뒤
 * 그 노선의 경유 정류소 목록을 따로 조회한다 (ADR 0001 "마스터 데이터 취득 방식이 바뀐다").
 */
class TagoBusRouteApi(
    private val client: TagoHttpClient,
    private val endpoint: WioProperties.Endpoint,
) {
    fun getRouteNoList(
        cityCode: String,
        routeNo: String,
    ): List<TagoRoute> =
        client
            .fetchAll(endpoint, OP_ROUTE_NO_LIST, mapOf("cityCode" to cityCode, "routeNo" to routeNo))
            .map(TagoRoute::from)

    fun getRouteAcctoThrghSttnList(
        cityCode: String,
        routeId: String,
    ): List<TagoRouteStop> =
        client
            .fetchAll(endpoint, OP_ROUTE_STOP_LIST, mapOf("cityCode" to cityCode, "routeId" to routeId))
            .map(TagoRouteStop::from)

    companion object {
        const val OP_ROUTE_NO_LIST = "getRouteNoList"
        const val OP_ROUTE_STOP_LIST = "getRouteAcctoThrghSttnList"
    }
}

// 필드 이름은 공공데이터포털 API 문서(Swagger) 설명만 보고 적었고 실 응답으로 검증하지 않았다.
// 실 키를 받으면 backend/README.md "실제 키를 받은 첫 세션의 확인 체크리스트"대로 fixture와 함께 갱신한다.
data class TagoRoute(
    val routeId: String,
    val routeNo: String,
    val routeTp: String,
    val startNodeNm: String,
    val endNodeNm: String,
) {
    companion object {
        fun from(item: Map<String, String>) =
            TagoRoute(
                routeId = item.str("routeid"),
                routeNo = item.str("routeno"),
                routeTp = item.str("routetp"),
                startNodeNm = item.str("startnodenm"),
                endNodeNm = item.str("endnodenm"),
            )
    }
}

data class TagoRouteStop(
    val nodeId: String,
    val nodeNm: String,
    val nodeNo: String,
    val nodeOrd: String,
    val gpsLati: String,
    val gpsLong: String,
    val updownCd: String,
) {
    val seqNo: Int? get() = nodeOrd.trim().toIntOrNull()
    val lat: Double? get() = gpsLati.trim().toDoubleOrNull()
    val lng: Double? get() = gpsLong.trim().toDoubleOrNull()

    companion object {
        fun from(item: Map<String, String>) =
            TagoRouteStop(
                nodeId = item.str("nodeid"),
                nodeNm = item.str("nodenm"),
                nodeNo = item.str("nodeno"),
                nodeOrd = item.str("nodeord"),
                gpsLati = item.str("gpslati"),
                gpsLong = item.str("gpslong"),
                updownCd = item.str("updowncd"),
            )
    }
}

internal fun Map<String, String>.str(key: String): String = this[key].orEmpty()
