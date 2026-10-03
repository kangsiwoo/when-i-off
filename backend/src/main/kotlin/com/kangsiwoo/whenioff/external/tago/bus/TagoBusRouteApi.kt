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

// 필드 이름은 #17에서 실 응답(화성 31240·성남 31020·대전 25)으로 대조했다. 실 응답에서 확인한 것:
//  - 숫자로 보이는 값은 JSON 숫자로 온다(`routeno: 4108`, `nodeord: 1`, `gpslati: 37.19`). 같은 필드가 값에
//    따라 문자열(`"M4108"`)이기도 하다 — `TagoHttpClient`가 전부 문자열로 바꿔 주므로 DTO는 문자열로 받는다.
//  - 선택 필드는 값이 없으면 키가 통째로 빠진다(`routetp`가 없는 DRT 노선, `nodeno`가 없는 미정차 지점).
//  - 노선번호 검색은 부분일치(포함)다 — `4108`을 찾으면 `M4108`·`M4108(예약)`도 같이 온다.
//  - 응답에는 `startvehicletime`/`endvehicletime`(첫차/막차 HHmm)도 있지만 쓰지 않아 매핑하지 않는다.
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

    /**
     * 경기(`GGB…`) 노선은 `updowncd`를 아예 주지 않는다 — 기점→회차→기점을 `nodeord` 하나로 이어서 주고
     * 왕복 정류장은 `nodeId`가 서로 다르다. 그래서 값이 없으면 노선 전체를 한 방향([SINGLE_DIRECTION])으로
     * 본다. 승·하차 방향은 `nodeord` 순서만으로 정해진다 (`LegDirectionResolver`). 대전 등은 `0`/`1`을 준다.
     */
    val directionCode: String get() = updownCd.trim().ifEmpty { SINGLE_DIRECTION }

    /** 경기 노선 정류소 목록에 섞여 오는 통과 지점(`…(미정차)`, `nodeno` 없음) — 탈 수 없는 곳이다. */
    val isPassThrough: Boolean get() = nodeNm.contains(PASS_THROUGH_MARK)

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

        const val SINGLE_DIRECTION = "0"
        const val PASS_THROUGH_MARK = "(미정차)"
    }
}

internal fun Map<String, String>.str(key: String): String = this[key].orEmpty()
