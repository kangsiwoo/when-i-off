import type { CallWindow, ExternalOp, OpsStatus } from "../api/client";

/**
 * 운영 화면(#76)의 판단 기준 — 화면 없는 순수 로직.
 *
 * 상태 색은 의미가 있을 때만(실패율·한도) 쓰고, 언제나 아이콘 + 글자와 함께 보인다(색만으로 뜻을 싣지 않는다).
 */

/** 이 실패율 이상이면 행을 강조한다 (오늘 또는 최근 1시간 중 하나라도). */
export const HIGH_FAILURE_RATE = 0.2;
/** 한도 사용률 경고 — ADR 0002의 Redis 재검토 기준(70%)과 같다. */
export const QUOTA_WARNING = 0.7;
/** 한도 사용률 위험 — 이 위로는 그날 남은 폴링이 위태롭다. */
export const QUOTA_CRITICAL = 0.9;

export type Level = "ok" | "warning" | "critical";

export const SOURCE_LABEL: Record<string, string> = {
  tago: "TAGO (버스)",
  klid: "KLID (신호등)",
};

export const sourceLabel = (source: string) => SOURCE_LABEL[source] ?? source.toUpperCase();

/** 오늘이나 최근 1시간 중 하나라도 실패율이 [HIGH_FAILURE_RATE] 이상. 호출이 없으면 아니다. */
export function isHighFailure(op: ExternalOp): boolean {
  return [op.today, op.lastHour].some(
    (w) => w.calls > 0 && (w.failureRate ?? 0) >= HIGH_FAILURE_RATE,
  );
}

export function quotaLevel(usageRate: number): Level {
  if (usageRate >= QUOTA_CRITICAL) return "critical";
  if (usageRate >= QUOTA_WARNING) return "warning";
  return "ok";
}

/** 0~1 비율을 `4.5%`처럼. 10% 미만은 소수 한 자리, 그 위는 정수, 0.1% 미만은 `<0.1%`. 없으면 `—`. */
export function formatRate(rate: number | null | undefined): string {
  if (rate == null) return "—";
  const pct = rate * 100;
  if (pct === 0) return "0%";
  if (pct < 0.1) return "<0.1%";
  return pct < 10 ? `${pct.toFixed(1)}%` : `${Math.round(pct)}%`;
}

export function formatMs(ms: number | null | undefined): string {
  if (ms == null) return "—";
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

const NUMBER = new Intl.NumberFormat("ko-KR");
export const formatCount = (n: number) => NUMBER.format(n);

const OUTCOME_LABEL: [keyof CallWindow["outcomes"], string][] = [
  ["httpError", "HTTP 오류"],
  ["apiError", "결과 코드 오류"],
  ["timeout", "타임아웃"],
  ["ioError", "I/O 오류"],
];

/** 실패 내역: `["결과 코드 오류 3", "타임아웃 1"]`. 실패가 없으면 빈 배열. */
export function failureBreakdown(w: CallWindow): string[] {
  return OUTCOME_LABEL.filter(([key]) => w.outcomes[key] > 0).map(
    ([key, label]) => `${label} ${w.outcomes[key]}`,
  );
}

/** 서버 재시작 이후 아무 외부 호출도 기록되지 않았다. */
export function hasNoCalls(status: OpsStatus): boolean {
  return status.sources.every((s) =>
    s.ops.every((op) => op.today.calls === 0 && op.lastHour.calls === 0),
  );
}

const KST_TIME = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});

/** 운영 시각은 초까지 (KST). */
export const formatKstSeconds = (iso: string) => KST_TIME.format(new Date(iso));
