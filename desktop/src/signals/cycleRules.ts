/**
 * 교차로 신호 주기 편집의 초안 ↔ 요청 변환, 클라이언트 검증, 미리보기 계산 (#78, 화면 없는 순수 로직).
 *
 * 검증은 서버 규칙을 **그대로** 옮긴 것이다. 서버를 바꾸면 여기도 같이 바꾼다.
 * - backend/src/main/kotlin/com/kangsiwoo/whenioff/signal/application/TrafficSignalCycleService.kt `validate`
 * - backend/src/main/kotlin/com/kangsiwoo/whenioff/signal/domain/SignalCycleRules.kt (주기 범위, 출처 우선순위)
 * - DB `traffic_signal_cycles` CHECK (`0 < red < cycle`)
 *
 * 화면은 문제를 한 번에 다 보여 주고, 그래도 서버가 400을 주면 그 `detail`을 그대로 보여 준다.
 */
import type {
  DayType,
  TrafficSignalCycle,
  TrafficSignalCycleRequest,
  SignalDataSource,
} from "../api/client";
import { dayTypeLabel } from "../format";

export const MIN_CYCLE_SEC = 30;
export const MAX_CYCLE_SEC = 300;
/** `ReplaceTrafficSignalCyclesRequest.cycles`의 `@Size(max = 100)`. */
export const MAX_ROWS = 100;

export const DAY_TYPES: readonly DayType[] = ["WEEKDAY", "SATURDAY", "SUNDAY_HOLIDAY"];

/** 시간대가 겹칠 때 앞 출처를 쓴다 (DATA_MODEL `traffic_signal_cycles`). */
export const SOURCE_PRIORITY: readonly SignalDataSource[] = [
  "USER_OBSERVED",
  "PUBLIC_API",
  "DEFAULT_ASSUMPTION",
];

const SOURCE_LABEL: Record<SignalDataSource, string> = {
  USER_OBSERVED: "직접 잰 값",
  PUBLIC_API: "공공 데이터",
  DEFAULT_ASSUMPTION: "기본 가정",
};
export const sourceLabel = (s: SignalDataSource) => SOURCE_LABEL[s];

/** analytics `defaults.DEFAULT_SIGNAL_CYCLE`. 그 시각을 담는 행이 하나도 없을 때 쓰는 값. */
export const DEFAULT_CYCLE = { cycleSec: 120, redSec: 90 } as const;

/** 편집 중인 사용자 행 하나. 입력칸 그대로라 숫자도 문자열이다. */
export interface CycleDraft {
  key: string;
  dayType: DayType;
  /** `HH:mm` 또는 `HH:mm:ss` (KST). */
  start: string;
  end: string;
  cycleSec: string;
  redSec: string;
}

let nextKey = 0;
const newKey = () => `cycle-${++nextKey}`;

export function newCycleDraft(dayType: DayType = "WEEKDAY"): CycleDraft {
  return { key: newKey(), dayType, start: "07:00", end: "10:00", cycleSec: "", redSec: "" };
}

/** 응답 시각(`HH:mm:ss`) → 입력칸 값. 초가 0이면 `HH:mm`. */
export function timeInputValue(t: string): string {
  return t.length >= 8 && t.endsWith(":00") ? t.slice(0, 5) : t.slice(0, 8);
}

/** 서버 목록 → 사용자 행 초안 (다른 출처는 읽기 전용이라 빠진다). */
export function draftsFromCycles(rows: TrafficSignalCycle[]): CycleDraft[] {
  return rows
    .filter((r) => r.source === "USER_OBSERVED")
    .map((r) => ({
      key: `cycle-row-${r.id}`,
      dayType: r.dayType,
      start: timeInputValue(r.timeBandStart),
      end: timeInputValue(r.timeBandEnd),
      cycleSec: String(r.cycleDurationSec),
      redSec: String(r.redDurationSec),
    }));
}

/** 초안 → `PUT /traffic-signals/{id}/cycles`의 `cycles`. 숫자가 아닌 값은 NaN으로 남겨 검증이 잡게 한다. */
export function toCycleRequests(drafts: CycleDraft[]): TrafficSignalCycleRequest[] {
  return drafts.map((d) => ({
    dayType: d.dayType,
    timeBandStart: d.start,
    timeBandEnd: d.end,
    cycleDurationSec: toInt(d.cycleSec),
    redDurationSec: toInt(d.redSec),
  }));
}

function toInt(raw: string): number {
  const s = raw.trim();
  return /^-?\d+$/.test(s) ? Number(s) : Number.NaN;
}

/** `HH:mm[:ss]` → 하루 중 초. 형식이 틀리면 null. 초 미만은 서버처럼 버린다. */
export function secondsOfDay(t: string): number | null {
  const m = /^(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/.exec(t.trim());
  if (!m) return null;
  const [h, min, s] = [Number(m[1]), Number(m[2]), Number(m[3] ?? 0)];
  if (h > 23 || min > 59 || s > 59) return null;
  return h * 3600 + min * 60 + s;
}

/** 화면 표시용 `HH:mm` / `HH:mm:ss`. */
export function formatBand(start: string, end: string): string {
  return `${timeInputValue(start)}–${timeInputValue(end)}`;
}

/**
 * 요청 목록의 문제를 서버와 같은 규칙으로 모은다. 빈 배열이면 서버 규칙상 400이 날 이유가 없다.
 * 행 번호는 화면의 1부터 (서버 `detail`의 `cycles[i]`는 0부터).
 */
export function validateCycleRequests(requests: TrafficSignalCycleRequest[]): string[] {
  const errors: string[] = [];
  if (requests.length > MAX_ROWS) errors.push(`주기 행은 ${MAX_ROWS}개까지입니다.`);
  const bands: { i: number; dayType: DayType; start: number; end: number }[] = [];
  requests.forEach((r, i) => {
    const p = `${i + 1}행`;
    const cycle = r.cycleDurationSec;
    const red = r.redDurationSec;
    const cycleOk = Number.isInteger(cycle) && cycle >= MIN_CYCLE_SEC && cycle <= MAX_CYCLE_SEC;
    if (!cycleOk) {
      errors.push(`${p}: 주기는 ${MIN_CYCLE_SEC}~${MAX_CYCLE_SEC}초 사이의 정수여야 합니다.`);
    }
    if (!Number.isInteger(red) || red <= 0) {
      errors.push(`${p}: 적색은 0보다 큰 정수(초)여야 합니다.`);
    } else if (cycleOk && red >= cycle) {
      errors.push(`${p}: 적색(${red}초)은 주기(${cycle}초)보다 짧아야 합니다.`);
    }
    const start = secondsOfDay(r.timeBandStart);
    const end = secondsOfDay(r.timeBandEnd);
    if (start == null || end == null) {
      errors.push(`${p}: 시간대는 HH:mm 형식이어야 합니다.`);
    } else if (start >= end) {
      errors.push(
        `${p}: 시작(${r.timeBandStart})이 끝(${r.timeBandEnd})보다 앞이어야 합니다. 자정을 넘는 시간대는 두 행으로 나눕니다.`,
      );
    } else {
      bands.push({ i, dayType: r.dayType, start, end });
    }
  });
  for (const dayType of DAY_TYPES) {
    const same = bands.filter((b) => b.dayType === dayType).sort((a, b) => a.start - b.start);
    for (let k = 1; k < same.length; k++) {
      const a = same[k - 1]!;
      const b = same[k]!;
      if (b.start < a.end) {
        errors.push(
          `${a.i + 1}행과 ${b.i + 1}행의 ${dayTypeLabel(dayType)} 시간대가 겹칩니다. 끝과 시작이 맞닿는 것은 됩니다.`,
        );
      }
    }
  }
  return errors;
}

export interface WaitPreview {
  /** 평균 대기 R²/(2C), 초. */
  meanWaitSec: number;
  /** 적색 비율 R/C (0~1). */
  redShare: number;
}

/** 주기 모델 미리보기 (ALGORITHM 2.1(a)). 값이 규칙에 맞지 않으면 null. */
export function waitPreview(cycleSec: number, redSec: number): WaitPreview | null {
  if (!(Number.isFinite(cycleSec) && Number.isFinite(redSec) && redSec > 0 && redSec < cycleSec)) {
    return null;
  }
  return { meanWaitSec: (redSec * redSec) / (2 * cycleSec), redShare: redSec / cycleSec };
}

/** 서버 행 정렬과 같은 순서 (day_type → 시작 → 출처 우선순위). */
export function sortCycles(rows: TrafficSignalCycle[]): TrafficSignalCycle[] {
  return [...rows].sort(
    (a, b) =>
      DAY_TYPES.indexOf(a.dayType) - DAY_TYPES.indexOf(b.dayType) ||
      (secondsOfDay(a.timeBandStart) ?? 0) - (secondsOfDay(b.timeBandStart) ?? 0) ||
      SOURCE_PRIORITY.indexOf(a.source) - SOURCE_PRIORITY.indexOf(b.source),
  );
}

/**
 * analytics가 그 시각에 고를 주기 (`io/signals.py` `resolve_cycle`과 같은 규칙): 그 day_type에서 시각을 담는
 * `[start, end)` 행 중 출처 우선순위가 가장 높은 것, 같은 출처면 시작이 이른 것. 없으면 기본값(null source).
 */
export function effectiveCycle(
  rows: TrafficSignalCycle[],
  dayType: DayType,
  timeOfDay: string,
): { cycleSec: number; redSec: number; source: SignalDataSource | null } {
  const at = secondsOfDay(timeOfDay);
  const contains = (r: TrafficSignalCycle) =>
    at != null &&
    r.dayType === dayType &&
    (secondsOfDay(r.timeBandStart) ?? Infinity) <= at &&
    at < (secondsOfDay(r.timeBandEnd) ?? -Infinity);
  // sortCycles가 시작 순으로 놓으므로 같은 출처끼리는 시작이 이른 행이 앞에 남는다 (sort는 안정 정렬).
  const hit = sortCycles(rows)
    .filter(contains)
    .sort((a, b) => SOURCE_PRIORITY.indexOf(a.source) - SOURCE_PRIORITY.indexOf(b.source))[0];
  return hit
    ? { cycleSec: hit.cycleDurationSec, redSec: hit.redDurationSec, source: hit.source }
    : { ...DEFAULT_CYCLE, source: null };
}
