package com.kangsiwoo.whenioff.external.klid.signal

import com.kangsiwoo.whenioff.common.config.WioProperties
import com.kangsiwoo.whenioff.external.klid.KlidHttpClient
import com.kangsiwoo.whenioff.external.klid.KlidResult
import com.kangsiwoo.whenioff.external.klid.KlidTime
import java.time.Instant

class KlidSignalApi(
    private val client: KlidHttpClient,
    private val endpoint: WioProperties.Endpoint,
) {
    fun crsrdMapInfo(stdgCd: String): KlidResult<Intersection> =
        client.fetchAll(endpoint, OP_CRSRD_MAP_INFO, stdgCd).map(Intersection::from)

    fun tlDrctInfo(stdgCd: String): KlidResult<SignalStateItem> =
        client.fetchAll(endpoint, OP_TL_DRCT_INFO, stdgCd).map(SignalStateItem::from)

    companion object {
        const val OP_CRSRD_MAP_INFO = "crsrd_map_info"
        const val OP_TL_DRCT_INFO = "tl_drct_info"
    }
}

data class Intersection(
    val stdgCd: String,
    val lclgvNm: String,
    val crsrdId: String,
    val crsrdNm: String,
    val mapCtptIntLat: String,
    val mapCtptIntLot: String,
    val laneWdth: String,
    val lmtSpdTypeNm: String,
    val lmtSpd: String,
    val crsrdEngNm: String,
    val regId: String,
    val regDt: String,
    val totDt: String,
) {
    val lat: Double? get() = mapCtptIntLat.trim().toDoubleOrNull()
    val lng: Double? get() = mapCtptIntLot.trim().toDoubleOrNull()

    companion object {
        fun from(item: Map<String, String>) =
            Intersection(
                stdgCd = item["stdgCd"].orEmpty(),
                lclgvNm = item["lclgvNm"].orEmpty(),
                crsrdId = item["crsrdId"].orEmpty(),
                crsrdNm = item["crsrdNm"].orEmpty(),
                mapCtptIntLat = item["mapCtptIntLat"].orEmpty(),
                mapCtptIntLot = item["mapCtptIntLot"].orEmpty(),
                laneWdth = item["laneWdth"].orEmpty(),
                lmtSpdTypeNm = item["lmtSpdTypeNm"].orEmpty(),
                lmtSpd = item["lmtSpd"].orEmpty(),
                crsrdEngNm = item["crsrdEngNm"].orEmpty(),
                regId = item["regId"].orEmpty(),
                regDt = item["regDt"].orEmpty(),
                totDt = item["totDt"].orEmpty(),
            )
    }
}

data class SignalStateItem(
    val stdgCd: String,
    val crsrdId: String,
    val totDt: String,
    val fields: Map<String, String>,
) {
    val observedAt: Instant? get() = KlidTime.parseKstOrNull(totDt)

    fun explode(): List<SignalStateRecord> = SignalStateExploder.explode(fields)

    companion object {
        fun from(item: Map<String, String>) =
            SignalStateItem(
                stdgCd = item["stdgCd"].orEmpty(),
                crsrdId = item["crsrdId"].orEmpty(),
                totDt = item["totDt"].orEmpty(),
                fields = item,
            )
    }
}
