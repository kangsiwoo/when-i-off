package com.kangsiwoo.whenioff.external.tago.bus

import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.tago.TagoHttpClient

/**
 * TAGO 버스도착정보(`ArvlInfoInqireService`) — 정류소별 특정노선 도착예정정보.
 *
 * 정류장 하나 + 노선 하나 단위로만 조회할 수 있어(`cityCode`+`nodeId`+`routeId`) 호출 수가
 * 등록된 구간 수에 비례한다 (ARCHITECTURE.md "TAGO: 필터가 노선/정류소 단위라는 것과 호출 한도").
 *
 * 오퍼레이션 이름은 포털 표기 그대로 **`Spcify`**(e 없음)다. 영어 철자대로 `Specify`로 보내면 게이트웨이가
 * `400 NO_OPENAPI_SERVICE_ERROR`(returnReasonCode 12)로 답한다 (#17에서 실 키로 확인).
 *
 * `cityCode`는 정류장 위치가 아니라 **노선을 등록한 지자체** 기준으로 거른다 — 성남 판교 정류장을
 * `31240`(화성)으로 물으면 화성 노선만, `31020`(성남)으로 물으면 성남 노선만 온다. 그래서 동기화 때
 * 노선을 찾은 `cityCode`(`transit_lines.stdg_cd`)를 그대로 넘긴다.
 */
class TagoArrivalApi(
    private val client: TagoHttpClient,
    private val endpoint: WioProperties.Endpoint,
) {
    fun getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList(
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
        const val OP_ARRIVAL_BY_STOP_AND_ROUTE = "getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList"
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
    /**
     * 도착까지 남은 시간(초). TAGO는 차량 번호를 주지 않고 차량유형(`vehicletp`)만 준다.
     * 실 응답의 `arrtime`/`arrprevstationcnt`는 JSON 숫자다 (`TagoHttpClient`가 문자열로 통일한다).
     * 같은 노선의 여러 차량은 `arrtime` 순으로 정렬되어 오지 않는다.
     */
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
