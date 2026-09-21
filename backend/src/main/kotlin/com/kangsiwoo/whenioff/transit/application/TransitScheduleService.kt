package com.kangsiwoo.whenioff.transit.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.common.domain.DayType
import com.kangsiwoo.whenioff.common.domain.DayTypeResolver
import com.kangsiwoo.whenioff.transit.api.NextDeparturesResponse
import com.kangsiwoo.whenioff.transit.api.ScheduledDepartureResponse
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitSchedule
import com.kangsiwoo.whenioff.transit.domain.TransitScheduleRepository
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Service
@Transactional
class TransitScheduleService(
    private val scheduleRepository: TransitScheduleRepository,
    private val lineRepository: TransitLineRepository,
    private val stopRepository: TransitStopRepository,
    private val dayTypeResolver: DayTypeResolver,
) {
    /**
     * CSV(`transit_line_id, transit_stop_id, day_type, scheduled_time`) 적재.
     *
     * 멱등성은 **(노선, 정류장, day_type) 단위 교체**로 얻는다. 파일에 등장한 조합의 기존 행을 지우고
     * 파일 내용을 넣으므로, 같은 파일을 두 번 넣어도 결과가 같고 개정으로 없어진 차편도 같이 사라진다.
     * UNIQUE 제약으로는 삭제분이 남기 때문에 제약을 새로 걸지 않았다 (#16 결정 3).
     */
    fun importCsv(content: String): SyncCounts {
        val rows = parseRows(content)
        if (rows.isEmpty()) throw BadRequestException("csv has no data row")
        val lines = lineRepository.findAllById(rows.mapTo(mutableSetOf()) { it.lineId }).associateBy { it.id!! }
        rows.firstOrNull { it.lineId !in lines }?.let {
            throw BadRequestException("row ${it.number}: unknown transit_line_id ${it.lineId}")
        }
        val stops = stopRepository.findAllById(rows.mapTo(mutableSetOf()) { it.stopId }).associateBy { it.id!! }
        rows.firstOrNull { it.stopId !in stops }?.let {
            throw BadRequestException("row ${it.number}: unknown transit_stop_id ${it.stopId}")
        }

        var created = 0
        var updated = 0
        var skipped = 0
        rows.groupBy { Key(it.lineId, it.stopId, it.dayType) }.forEach { (key, group) ->
            val existing =
                scheduleRepository.findByTransitLineIdAndStopIdAndDayType(key.lineId, key.stopId, key.dayType)
            val existingTimes = existing.mapTo(mutableSetOf()) { it.scheduledTime }
            val times = linkedSetOf<LocalTime>()
            group.forEach { if (!times.add(it.scheduledTime)) skipped++ }
            times.forEach { if (it in existingTimes) updated++ else created++ }
            scheduleRepository.deleteAllInBatch(existing)
            scheduleRepository.saveAll(
                times.map { TransitSchedule(lines.getValue(key.lineId), stops.getValue(key.stopId), key.dayType, it) },
            )
        }
        return SyncCounts(fetched = rows.size, created = created, updated = updated, skipped = skipped)
    }

    /** 기준 시각 `at` 이후 출발하는 `limit`대. 오늘 차편이 모자라면 다음 날로 이어진다. */
    @Transactional(readOnly = true)
    fun nextDepartures(
        lineId: Long,
        stopId: Long,
        at: Instant,
        limit: Int,
    ): NextDeparturesResponse {
        if (limit !in 1..MAX_LIMIT) throw BadRequestException("limit must be between 1 and $MAX_LIMIT")
        if (!lineRepository.existsById(lineId)) throw NotFoundException("transit line $lineId not found")
        if (!stopRepository.existsById(stopId)) throw NotFoundException("transit stop $stopId not found")

        val kstNow = at.atZone(DayTypeResolver.KST)
        val today = kstNow.toLocalDate()
        val departures = take(lineId, stopId, today, kstNow.toLocalTime(), limit)
        // 막차/첫차 경계: 오늘 남은 차편이 모자라면 다음 날 00:00부터 이어 본다. 다음 날은 day_type이
        // 다를 수 있으므로(금→토, 일→월, 공휴일 전날) 날짜별로 다시 판정한다.
        val remaining = limit - departures.size
        return NextDeparturesResponse(
            transitLineId = lineId,
            stopId = stopId,
            departures =
                if (remaining > 0) {
                    departures + take(lineId, stopId, today.plusDays(1), LocalTime.MIN, remaining)
                } else {
                    departures
                },
        )
    }

    private fun take(
        lineId: Long,
        stopId: Long,
        date: LocalDate,
        from: LocalTime,
        limit: Int,
    ): List<ScheduledDepartureResponse> {
        val dayType = dayTypeResolver.resolve(date)
        return scheduleRepository
            .findByTransitLineIdAndStopIdAndDayTypeAndScheduledTimeGreaterThanEqualOrderByScheduledTimeAsc(
                lineId,
                stopId,
                dayType,
                from,
                PageRequest.of(0, limit),
            ).map {
                ScheduledDepartureResponse(
                    serviceDate = date,
                    dayType = dayType,
                    scheduledTime = it.scheduledTime,
                    // KST는 서머타임이 없어 (운행일 + 시간표 시각)이 항상 한 순간으로만 해석된다.
                    departureAt = date.atTime(it.scheduledTime).atZone(DayTypeResolver.KST).toInstant(),
                )
            }
    }

    private fun parseRows(content: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        var atFirstLine = true
        content.lineSequence().forEachIndexed { index, raw ->
            // 엑셀에서 내보낸 CSV는 BOM으로 시작해서 첫 칸의 숫자 파싱이 깨진다.
            val line = raw.trim().trimStart(Char(0xFEFF))
            if (line.isEmpty()) return@forEachIndexed
            val isHeader = atFirstLine && line.substringBefore(',').trim().equals(FIRST_COLUMN, ignoreCase = true)
            atFirstLine = false
            // 헤더는 첫 줄이면서 첫 칸이 컬럼명일 때만 건너뛴다. "첫 칸이 숫자가 아니면 헤더"로 보면
            // 깨진 데이터 행이 오류 없이 조용히 사라진다.
            if (isHeader) return@forEachIndexed
            rows += parseRow(index + 1, line)
        }
        return rows
    }

    private fun parseRow(
        number: Int,
        line: String,
    ): CsvRow {
        val cells = line.split(',').map { it.trim() }
        if (cells.size != COLUMNS) {
            throw BadRequestException("row $number: expected $COLUMNS columns ($COLUMN_NAMES) but got ${cells.size}")
        }
        val lineId =
            cells[0].toLongOrNull() ?: throw BadRequestException("row $number: transit_line_id must be a number")
        val stopId =
            cells[1].toLongOrNull() ?: throw BadRequestException("row $number: transit_stop_id must be a number")
        val dayType =
            DayType.entries.firstOrNull { it.name == cells[2].uppercase() }
                ?: throw BadRequestException(
                    "row $number: unknown day_type '${cells[2]}' (${DayType.entries.joinToString()})",
                )
        val scheduledTime =
            runCatching { LocalTime.parse(cells[3]) }.getOrElse {
                throw BadRequestException("row $number: scheduled_time '${cells[3]}' must be HH:mm or HH:mm:ss (KST)")
            }
        return CsvRow(number, lineId, stopId, dayType, scheduledTime)
    }

    private data class CsvRow(
        val number: Int,
        val lineId: Long,
        val stopId: Long,
        val dayType: DayType,
        val scheduledTime: LocalTime,
    )

    private data class Key(
        val lineId: Long,
        val stopId: Long,
        val dayType: DayType,
    )

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 50
        private const val COLUMNS = 4
        private const val FIRST_COLUMN = "transit_line_id"
        private const val COLUMN_NAMES = "transit_line_id, transit_stop_id, day_type, scheduled_time"
    }
}
