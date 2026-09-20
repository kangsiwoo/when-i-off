package com.kangsiwoo.whenioff.external.klid.bus

import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.klid.KlidHttpClient
import com.kangsiwoo.whenioff.external.klid.KlidTime
import java.time.Instant

class KlidBusApi(
    private val client: KlidHttpClient,
    private val endpoint: WioProperties.Endpoint,
) {
    fun mstInfo(stdgCd: String): List<BusRouteMaster> =
        client.fetchAll(endpoint, OP_MST_INFO, stdgCd).map(BusRouteMaster::from)

    fun psInfo(stdgCd: String): List<BusRouteStop> =
        client.fetchAll(endpoint, OP_PS_INFO, stdgCd).map(BusRouteStop::from)

    fun rtmLocInfo(stdgCd: String): List<BusVehiclePosition> =
        client.fetchAll(endpoint, OP_RTM_LOC_INFO, stdgCd).map(BusVehiclePosition::from)

    companion object {
        const val OP_MST_INFO = "mst_info"
        const val OP_PS_INFO = "ps_info"
        const val OP_RTM_LOC_INFO = "rtm_loc_info"
    }
}

data class BusRouteMaster(
    val stdgCd: String,
    val lclgvNm: String,
    val rteId: String,
    val rteNo: String,
    val rteType: String,
    val stpnt: String,
    val edpnt: String,
    val vhclFstTm: String,
    val vhclLstTm: String,
    val totDt: String,
) {
    companion object {
        fun from(item: Map<String, String>) =
            BusRouteMaster(
                stdgCd = item.str("stdgCd"),
                lclgvNm = item.str("lclgvNm"),
                rteId = item.str("rteId"),
                rteNo = item.str("rteNo"),
                rteType = item.str("rteType"),
                stpnt = item.str("stpnt"),
                edpnt = item.str("edpnt"),
                vhclFstTm = item.str("vhclFstTm"),
                vhclLstTm = item.str("vhclLstTm"),
                totDt = item.str("totDt"),
            )
    }
}

data class BusRouteStop(
    val stdgCd: String,
    val lclgvNm: String,
    val rteId: String,
    val bstaId: String,
    val bstaNm: String,
    val bstaNo: String,
    val bstaSn: String,
    val bstaLat: String,
    val bstaLot: String,
    val drcGbnCd: String,
    val totDt: String,
) {
    val seqNo: Int? get() = bstaSn.trim().toIntOrNull()
    val lat: Double? get() = bstaLat.trim().toDoubleOrNull()
    val lng: Double? get() = bstaLot.trim().toDoubleOrNull()

    companion object {
        fun from(item: Map<String, String>) =
            BusRouteStop(
                stdgCd = item.str("stdgCd"),
                lclgvNm = item.str("lclgvNm"),
                rteId = item.str("rteId"),
                bstaId = item.str("bstaId"),
                bstaNm = item.str("bstaNm"),
                bstaNo = item.str("bstaNo"),
                bstaSn = item.str("bstaSn"),
                bstaLat = item.str("bstaLat"),
                bstaLot = item.str("bstaLot"),
                drcGbnCd = item.str("drcGbnCd"),
                totDt = item.str("totDt"),
            )
    }
}

data class BusVehiclePosition(
    val stdgCd: String,
    val lclgvNm: String,
    val rteId: String,
    val vhclNo: String,
    val gthrDt: String,
    val rteNo: String,
    val lat: String,
    val lot: String,
    val oprDrct: String,
    val oprSpd: String,
    val evtCd: String,
    val evtType: String,
    val totDt: String,
    val raw: Map<String, String>,
) {
    val latitude: Double? get() = lat.trim().toDoubleOrNull()
    val longitude: Double? get() = lot.trim().toDoubleOrNull()
    val speedKmh: Double? get() = oprSpd.trim().toDoubleOrNull()
    val headingDeg: Double? get() = oprDrct.trim().toDoubleOrNull()
    val observedAt: Instant? get() = KlidTime.parseKstOrNull(gthrDt) ?: KlidTime.parseKstOrNull(totDt)

    companion object {
        fun from(item: Map<String, String>) =
            BusVehiclePosition(
                stdgCd = item.str("stdgCd"),
                lclgvNm = item.str("lclgvNm"),
                rteId = item.str("rteId"),
                vhclNo = item.str("vhclNo"),
                gthrDt = item.str("gthrDt"),
                rteNo = item.str("rteNo"),
                lat = item.str("lat"),
                lot = item.str("lot"),
                oprDrct = item.str("oprDrct"),
                oprSpd = item.str("oprSpd"),
                evtCd = item.str("evtCd"),
                evtType = item.str("evtType"),
                totDt = item.str("totDt"),
                raw = item,
            )
    }
}

private fun Map<String, String>.str(key: String): String = this[key].orEmpty()
