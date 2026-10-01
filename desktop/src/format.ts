import type { AttemptResult, CommuteRoute, DayType, RouteLeg } from "./api/client";

const DIRECTION: Record<CommuteRoute["direction"], string> = {
  TO_WORK: "출근",
  TO_HOME: "퇴근",
};

const LEG_TYPE: Record<RouteLeg["legType"], string> = {
  WALK: "도보",
  TRANSIT: "대중교통",
};

type TransitMode = NonNullable<RouteLeg["transitLine"]>["mode"];

const MODE: Record<TransitMode, string> = {
  BUS: "버스",
  SUBWAY: "지하철",
  GTX: "GTX",
};

const RESULT: Record<AttemptResult, string> = {
  CAUGHT: "탐",
  MISSED: "놓침",
  UNKNOWN: "모름",
};

const DAY_TYPE: Record<DayType, string> = {
  WEEKDAY: "평일",
  SATURDAY: "토요일",
  SUNDAY_HOLIDAY: "일요일·공휴일",
};

export const dayTypeLabel = (d: DayType) => DAY_TYPE[d];
export const resultLabel = (r: AttemptResult) => RESULT[r];
export const directionLabel = (d: CommuteRoute["direction"]) => DIRECTION[d];
export const legTypeLabel = (t: RouteLeg["legType"]) => LEG_TYPE[t];
export const modeLabel = (m: TransitMode) => MODE[m];

// 저장·전송은 UTC, 표시할 때만 KST (docs/CONVENTIONS.md 공통 규칙).
const KST = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  dateStyle: "medium",
  timeStyle: "short",
});

export const formatKst = (iso: string) => KST.format(new Date(iso));

export function formatDistance(m: number | null | undefined): string {
  if (m == null) return "—";
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

export function formatDuration(sec: number | null | undefined): string {
  if (sec == null) return "—";
  const min = Math.floor(sec / 60);
  const rest = sec % 60;
  if (min === 0) return `${rest}초`;
  return rest === 0 ? `${min}분` : `${min}분 ${rest}초`;
}

/** 예측 대비 차이처럼 부호가 있는 초. 0이 아니면 항상 부호를 붙인다. */
export function formatSignedSec(sec: number): string {
  if (sec === 0) return "±0초";
  return `${sec > 0 ? "+" : "−"}${formatDuration(Math.abs(sec))}`;
}

/** TRANSIT 구간 한 줄 이름: `GTX-A 동탄 → 수서`. */
export function transitLegLabel(leg: RouteLeg | undefined, legId: number): string {
  if (!leg) return `구간 #${legId}`;
  const line = leg.transitLine?.name ?? "노선 ?";
  return `${line} ${leg.boardStop?.name ?? "?"} → ${leg.alightStop?.name ?? "?"}`;
}

/** 도보 속도 `1.31 ± 0.12 m/s`. */
export function formatSpeed(mean: number, stddev: number): string {
  return `${mean.toFixed(2)} ± ${stddev.toFixed(2)} m/s`;
}

/** 평균 ± σ(초). `signed`면 평균에 부호를 붙인다 (예측 오차 = 실제 − 예측). */
export function formatSecSpread(mean: number, stddev: number, signed = false): string {
  const m = Math.round(mean);
  return `${signed ? formatSignedSec(m) : formatDuration(m)} ± ${formatDuration(Math.round(stddev))}`;
}
