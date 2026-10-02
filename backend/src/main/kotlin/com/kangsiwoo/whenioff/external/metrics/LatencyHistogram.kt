package com.kangsiwoo.whenioff.external.metrics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 지연(ms)의 로그 눈금 히스토그램 — HdrHistogram을 아주 작게 줄인 것 (#76).
 *
 * 칸 경계는 2의 1/8 거듭제곱(약 9%씩)이라 백분위 값의 상대 오차가 9% 안쪽이다. 칸 0은 1ms 미만, 마지막 칸은
 * 2^17ms(약 131초) 이상을 모두 담는다. 칸이 고정이라 메모리가 표본 수와 상관없고 서로 더할 수 있어서, 분 단위
 * 칸을 합쳐 최근 1시간을 만든다. 최소·최대는 정확히 따로 들고 있다가 백분위 값을 그 사이로 자른다.
 */
class LatencyHistogram {
    private val counts = LongArray(SIZE)
    var count: Long = 0
        private set
    private var min = Double.MAX_VALUE
    private var max = 0.0

    fun record(ms: Double) {
        val v = ms.coerceAtLeast(0.0)
        counts[indexOf(v)]++
        count++
        if (v < min) min = v
        if (v > max) max = v
    }

    fun add(other: LatencyHistogram) {
        for (i in counts.indices) counts[i] += other.counts[i]
        count += other.count
        if (other.count > 0) {
            if (other.min < min) min = other.min
            if (other.max > max) max = other.max
        }
    }

    /**
     * nearest-rank 백분위(`q`는 0~1). 그 순위가 든 칸의 위쪽 경계를 돌려주되 관측한 최소·최대 밖으로는 나가지 않는다
     * — 값이 하나뿐이면 그 값 그대로다. 표본이 없으면 null.
     */
    fun percentileMs(q: Double): Long? {
        if (count == 0L) return null
        val rank = ceil(q * count).toLong().coerceIn(1, count)
        var seen = 0L
        for (i in counts.indices) {
            seen += counts[i]
            if (seen >= rank) return upperBound(i).coerceIn(min, max).roundToLong()
        }
        return max.roundToLong()
    }

    companion object {
        private const val SUB_BUCKETS = 8
        private const val MAX_POWER = 17
        private const val SIZE = MAX_POWER * SUB_BUCKETS + 2

        internal fun indexOf(ms: Double): Int {
            if (ms < 1.0) return 0
            val i = floor(ln(ms) / ln(2.0) * SUB_BUCKETS).toInt() + 1
            return i.coerceAtMost(SIZE - 1)
        }

        /** 칸 `i`(1 이상)는 `[2^((i-1)/8), 2^(i/8))` ms. 마지막 칸은 위가 열려 있다. */
        internal fun upperBound(i: Int): Double =
            when (i) {
                0 -> 1.0
                SIZE - 1 -> Double.MAX_VALUE
                else -> 2.0.pow(i.toDouble() / SUB_BUCKETS)
            }
    }
}
