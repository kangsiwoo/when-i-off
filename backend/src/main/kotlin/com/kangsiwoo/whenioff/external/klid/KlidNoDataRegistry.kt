package com.kangsiwoo.whenioff.external.klid

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * `tl_drct_info`가 **K3(NODATA)** 로 답한 `stdgCd`를 TTL 동안 기억해 폴링이 건너뛰게 한다 (#31).
 *
 * K3는 "이 지자체에는 실시간 신호가 제공되지 않는다"는 지자체의 성질이라 잠시 캐시해도 안전하다.
 * 반대로 `K0` + 0건은 "지금 이 순간 보고가 없다"는 일시적 상태이므로 **절대 여기에 넣지 않는다** —
 * 넣으면 실제로 커버되는 지자체의 관측을 TTL 동안 조용히 잃는다.
 *
 * 저장소는 메모리다. 재시작하면 비어 있고 지자체마다 한 번씩 다시 찔러본 뒤 채워지는데, 사이클당
 * 호출 수에 비하면 무시할 수 있는 비용이고 대신 낡은 표시가 프로세스 수명을 넘겨 남지 않는다.
 *
 * TTL이 지나면 표시가 사라져 다음 사이클이 실제로 호출한다. 커버리지가 생겼으면 그대로 적재되고,
 * 여전히 K3면 [mark]로 다시 걸린다 — 사람이 설정을 고칠 필요가 없다.
 */
class KlidNoDataRegistry(
    val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val markedUntil = ConcurrentHashMap<String, Instant>()

    /** 지금 이 `stdgCd`를 건너뛰어야 하는가. TTL이 지난 표시는 읽는 김에 지운다. */
    fun isMarked(stdgCd: String): Boolean {
        val until = markedUntil[stdgCd] ?: return false
        if (!clock.instant().isBefore(until)) {
            markedUntil.remove(stdgCd, until)
            return false
        }
        return true
    }

    /** K3를 받았을 때만 호출한다. TTL이 0 이하면 표시가 즉시 만료되어 기능이 꺼진 것과 같다. */
    fun mark(stdgCd: String) {
        markedUntil[stdgCd] = clock.instant().plus(ttl)
    }

    fun clear() = markedUntil.clear()
}
