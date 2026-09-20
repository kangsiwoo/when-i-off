package com.kangsiwoo.whenioff.signal.application

import com.kangsiwoo.whenioff.external.klid.signal.Intersection
import com.kangsiwoo.whenioff.external.klid.signal.KlidSignalApi
import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import com.kangsiwoo.whenioff.transit.application.SyncCounts
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

data class IntersectionSyncResult(
    val stdgCd: String,
    val intersections: SyncCounts,
)

@Service
class IntersectionSyncService(
    private val signalApi: KlidSignalApi,
    private val signalRepository: TrafficSignalRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun syncIntersections(stdgCd: String): IntersectionSyncResult {
        val intersections = signalApi.crsrdMapInfo(stdgCd)
        return transactionTemplate.execute { persist(stdgCd, intersections) }!!
    }

    private fun persist(
        stdgCd: String,
        intersections: List<Intersection>,
    ): IntersectionSyncResult {
        val byCrsrdId = signalRepository.findAllByStdgCd(stdgCd).associateBy { it.crsrdId }.toMutableMap()
        var created = 0
        var updated = 0
        var skipped = 0
        for (item in intersections.distinctBy { it.crsrdId }) {
            val lat = item.lat
            val lng = item.lng
            if (item.crsrdId.isBlank() || lat == null || lng == null) {
                skipped++
                continue
            }
            val name = item.crsrdNm.ifBlank { null }
            val existing = byCrsrdId[item.crsrdId]
            if (existing == null) {
                byCrsrdId[item.crsrdId] =
                    signalRepository.save(
                        TrafficSignal(lat = lat, lng = lng, stdgCd = stdgCd, crsrdId = item.crsrdId, name = name),
                    )
                created++
            } else if (existing.lat != lat || existing.lng != lng || existing.name != name) {
                existing.lat = lat
                existing.lng = lng
                existing.name = name
                updated++
            }
        }
        val result = IntersectionSyncResult(stdgCd, SyncCounts(intersections.size, created, updated, skipped))
        log.info { "intersection sync $stdgCd: $result" }
        return result
    }
}
