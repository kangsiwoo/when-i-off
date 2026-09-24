package com.kangsiwoo.whenioff.signal.application

import com.kangsiwoo.whenioff.external.klid.signal.KlidSignalApi
import com.kangsiwoo.whenioff.external.klid.signal.SignalStateItem
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalStateRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

data class SignalIngestResult(
    val stdgCd: String,
    val intersectionsFetched: Int,
    val intersectionsMatched: Int,
    val statesInserted: Int,
    val statesDuplicate: Int,
    /**
     * KLID가 K3(NODATA)로 답했다 = 이 지자체에는 실시간 신호가 제공되지 않는다(#30).
     * K0인데 항목이 0건인 경우(일시적으로 보고가 없음)는 여기서 `false`다.
     */
    val noData: Boolean = false,
)

@Service
class SignalStateIngestService(
    private val signalApi: KlidSignalApi,
    private val signalRepository: TrafficSignalRepository,
    private val stateRepository: TrafficSignalStateRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun ingest(stdgCd: String): SignalIngestResult {
        val fetched = signalApi.tlDrctInfo(stdgCd)
        return transactionTemplate.execute { persist(stdgCd, fetched.items, fetched.noData) }!!
    }

    private fun persist(
        stdgCd: String,
        items: List<SignalStateItem>,
        noData: Boolean,
    ): SignalIngestResult {
        val signalsByCrsrdId = signalRepository.findAllByStdgCd(stdgCd).associateBy { it.crsrdId }
        var matched = 0
        var inserted = 0
        var duplicate = 0
        for (item in items) {
            val signal = signalsByCrsrdId[item.crsrdId] ?: continue
            val observedAt = item.observedAt ?: continue
            matched++
            for (record in item.explode()) {
                val affected =
                    stateRepository.insertIgnoringDuplicate(
                        signalId = signal.id!!,
                        observedAt = observedAt,
                        approachDir = record.approachDir,
                        signalKind = record.signalKind,
                        status = record.status,
                        remainingDs = record.remainingDs,
                    )
                if (affected > 0) inserted++ else duplicate++
            }
        }
        val result = SignalIngestResult(stdgCd, items.size, matched, inserted, duplicate, noData)
        log.info { "signal state ingest $stdgCd: $result" }
        return result
    }
}
