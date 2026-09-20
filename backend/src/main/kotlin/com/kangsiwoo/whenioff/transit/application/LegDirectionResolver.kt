package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.transit.domain.TransitLineStopRepository
import org.springframework.stereotype.Component

@Component
class LegDirectionResolver(
    private val lineStopRepository: TransitLineStopRepository,
) {
    // A stop can belong to both directions of a line; prefer the direction where the alight stop comes after it.
    fun resolve(
        lineId: Long,
        boardStopId: Long,
        alightStopId: Long?,
    ): String? {
        val stopIds = listOfNotNull(boardStopId, alightStopId).distinct()
        val lineStops = lineStopRepository.findAllByLineAndStopIds(lineId, stopIds)
        val boardEntries = lineStops.filter { it.stop.id == boardStopId }
        if (boardEntries.isEmpty()) return null
        if (alightStopId != null) {
            val alightByDirection = lineStops.filter { it.stop.id == alightStopId }.groupBy { it.directionCode }
            boardEntries
                .firstOrNull { board ->
                    alightByDirection[board.directionCode]?.any { it.seqNo > board.seqNo } == true
                }?.let { return it.directionCode }
        }
        return boardEntries.minBy { it.directionCode }.directionCode
    }
}
