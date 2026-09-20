package com.kangsiwoo.whenioff.trip.infra

import com.kangsiwoo.whenioff.trip.domain.GpsPoint
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class GpsTraceJdbcRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    // 중복은 UNIQUE(user_id, recorded_at) + ON CONFLICT DO NOTHING으로 흡수하고,
    // 실제 삽입 수는 RETURNING 행 수로 센다 (batch update count는 드라이버가 SUCCESS_NO_INFO를 줄 수 있음)
    fun insertIgnoringDuplicates(
        userId: Long,
        commuteTripId: Long?,
        points: List<GpsPoint>,
    ): Int {
        if (points.isEmpty()) return 0
        val sql =
            "INSERT INTO gps_traces (user_id, commute_trip_id, recorded_at, lat, lng, speed_mps, accuracy_m) VALUES " +
                points.joinToString(", ") { "(?, ?, ?, ?, ?, ?, ?)" } +
                " ON CONFLICT (user_id, recorded_at) DO NOTHING RETURNING id"
        val insertedIds =
            jdbcTemplate.query(
                sql,
                { ps ->
                    points.forEachIndexed { i, p ->
                        val base = i * COLUMNS
                        ps.setLong(base + 1, userId)
                        ps.setObject(base + 2, commuteTripId, Types.BIGINT)
                        ps.setObject(base + 3, OffsetDateTime.ofInstant(p.recordedAt, ZoneOffset.UTC))
                        ps.setDouble(base + 4, p.lat)
                        ps.setDouble(base + 5, p.lng)
                        ps.setObject(base + 6, p.speedMps, Types.DOUBLE)
                        ps.setObject(base + 7, p.accuracyM, Types.DOUBLE)
                    }
                },
            ) { rs, _ -> rs.getLong(1) }
        return insertedIds.size
    }

    private companion object {
        const val COLUMNS = 7
    }
}
