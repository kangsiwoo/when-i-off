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
)

@Service
class SignalStateIngestService(
    private val signalApi: KlidSignalApi,
    private val signalRepository: TrafficSignalRepository,
    private val stateRepository: TrafficSignalStateRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun ingest(stdgCd: String): SignalIngestResult {
        val items = signalApi.tlDrctInfo(stdgCd)
        return transactionTemplate.execute { persist(stdgCd, items) }!!
    }

    private fun persist(
        stdgCd: String,
        items: List<SignalStateItem>,
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
        val result = SignalIngestResult(stdgCd, items.size, matched, inserted, duplicate)
        log.info { "signal state ingest $stdgCd: $result" }
        return result
    }
}
