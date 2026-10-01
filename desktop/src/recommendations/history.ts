import type {
  DepartureRecommendation,
  RecommendationHistoryDay,
  RecommendationHistoryTrip,
} from "../api/client";
import { addDays, kstMinutesOfDay } from "../kst";

// 추천 vs 실제(#62)의 파생값. API는 날짜별 추천·trip을 묶어 주기만 하고, 차이·지각·차트 계열은 여기서 만든다.
// 시각 계산은 모두 UTC 밀리초 차이거나 kst.ts의 고정 +9시간이라 브라우저 시간대와 무관하다.

/** `b`에서 `a`까지 초(`a − b`). 반올림한다. */
export function diffSec(a: string, b: string): number {
  return Math.round((Date.parse(a) - Date.parse(b)) / 1000);
}

/** 실제 출발 − 추천 출발(초). 양수면 추천보다 늦게 나갔다. 집 나선 시각이 없으면 null. */
export function leaveDiffSec(
  trip: RecommendationHistoryTrip,
  recommendation: DepartureRecommendation,
): number | null {
  return trip.leftHomeAt ? diffSec(trip.leftHomeAt, recommendation.recommendedLeaveHomeAt) : null;
}

export interface Arrival {
  /** 실제 도착 − 목표 도착(초). 양수면 늦었다. */
  diffSec: number;
  /** 목표 시각보다 1초라도 늦게 도착했으면 지각이다. 딱 맞으면 지각이 아니다. */
  late: boolean;
}

/** 목표 도착 대비 실제 도착. 도착 기록이나 목표 시각이 없으면 null. */
export function arrivalAgainstTarget(
  trip: RecommendationHistoryTrip,
  targetArrivalAt: string | null | undefined,
): Arrival | null {
  if (!trip.arrivedDestinationAt || !targetArrivalAt) return null;
  const sec = diffSec(trip.arrivedDestinationAt, targetArrivalAt);
  return { diffSec: sec, late: sec > 0 };
}

/** `v2` < `v10`처럼 숫자를 숫자로 비교하는 버전 순서. */
export const compareVersions = (a: string, b: string) =>
  a.localeCompare(b, "en", { numeric: true, sensitivity: "base" });

/**
 * 그날 지각 판정의 기준 추천: 가장 늦게 계산된 것(같으면 뒤 버전). 버전마다 목표 시각이 같은 게 보통이지만
 * 다르면 이 추천의 목표 시각을 쓴다.
 */
export function referenceRecommendation(
  recommendations: DepartureRecommendation[],
): DepartureRecommendation | null {
  let best: DepartureRecommendation | null = null;
  for (const r of recommendations) {
    if (
      !best ||
      Date.parse(r.computedAt) > Date.parse(best.computedAt) ||
      (r.computedAt === best.computedAt && compareVersions(r.modelVersion, best.modelVersion) > 0)
    ) {
      best = r;
    }
  }
  return best;
}

/**
 * 부호 있는 분. `±0분`, `+3분`, `−12분`. 1분이 안 되는 차이는 0으로 뭉개지 않고 `+1분 미만`으로 쓴다
 * (지각인데 `+0분`이라고 쓰면 판정과 숫자가 어긋나 보인다).
 */
export function formatSignedMinutes(sec: number): string {
  if (sec === 0) return "±0분";
  const sign = sec > 0 ? "+" : "−";
  const min = Math.round(Math.abs(sec) / 60);
  return min === 0 ? `${sign}1분 미만` : `${sign}${min}분`;
}

/** 분(0 이상, 반올림). buffer 표시용. */
export function formatMinutes(sec: number): string {
  return `${Math.round(sec / 60)}분`;
}

/** 축·툴팁용 하루 중 시각(분) → `HH:mm`. 자정을 넘긴 값은 다음 날 시각으로 감는다. */
export function formatMinuteOfDay(minutes: number): string {
  const m = ((Math.round(minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

/** `yyyy-MM-dd` → `9/21` (축 눈금). */
export function shortDate(date: string): string {
  return `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}`;
}

// --- 계열 색 ---

/** 차트 계열의 색 역할. `series-1`은 실제 출발이 쓰고, 버전은 2·3번 자리나 회색(other)이다. */
export type SeriesSlot = "series-1" | "series-2" | "series-3" | "other";

/**
 * 버전 → 색 자리. 색은 버전(개체)을 따라가야 하므로 화면에 몇 개가 보이는지가 아니라 버전 번호로 정한다:
 * `vN`의 N이 홀수면 2번, 짝수면 3번 자리. 출시가 v1 → v2 → v3로 이어지는 한 나란히 보이는 두 버전은 늘
 * 다른 색이고, 기간을 바꿔도 같은 버전은 같은 색이다. 선이 교차하는 차트라 세 색(실제 + 두 버전)까지만
 * 모든 쌍이 색각 이상 검사를 통과하므로, 같은 자리를 원하는 더 오래된 버전과 번호가 없는 버전은 회색으로 접는다.
 */
export function versionSlots(versions: string[]): Map<string, SeriesSlot> {
  const slots = new Map<string, SeriesSlot>();
  const numbered = versions
    .map((v) => ({ v, n: /^v(\d+)$/i.exec(v)?.[1] }))
    .filter((x): x is { v: string; n: string } => x.n !== undefined)
    .sort((a, b) => Number(b.n) - Number(a.n));
  const taken = new Set<SeriesSlot>();
  for (const { v, n } of numbered) {
    const slot: SeriesSlot = Number(n) % 2 === 1 ? "series-2" : "series-3";
    if (taken.has(slot)) {
      slots.set(v, "other");
    } else {
      taken.add(slot);
      slots.set(v, slot);
    }
  }
  for (const v of versions) if (!slots.has(v)) slots.set(v, "other");
  return slots;
}

// --- 차트 계열 ---

/**
 * 차트 한 줄(하루). Recharts는 문자열 dataKey를 경로로 읽어(`v1.2` → v1의 2) 버전 문자열을 키로 쓰지 않고
 * `versions`의 순번을 쓴다: `rec0`, `rec1`… / `buf0`… / 실제 출발은 그날 trip 순번 `actual0`, `actual1`….
 */
export interface ChartRow {
  date: string;
  /** 이 날 추천이나 trip이 하나라도 있는가 (없는 날은 축의 빈 칸이다). */
  hasData: boolean;
  [key: string]: number | string | boolean | null;
}

export interface ChartSeries {
  /** 등장한 버전, 버전 순. 차트 계열 키 `rec{i}`·`buf{i}`의 i가 이 배열의 순번이다. */
  versions: string[];
  /** 하루에 trip이 가장 많은 날의 trip 수 (`actual{i}` 계열 수). */
  maxTrips: number;
  /** 첫 기록일부터 마지막 기록일까지 빠짐없는 날짜(기록 없는 날은 값이 모두 null). */
  rows: ChartRow[];
  /** 출발 시각 차트의 y 범위(분)와 눈금. */
  timeDomain: [number, number];
  timeTicks: number[];
  /** buffer 차트의 y 범위(분, 0부터)와 눈금. */
  bufferDomain: [number, number];
  bufferTicks: number[];
}

export const recKey = (i: number) => `rec${i}`;
export const bufKey = (i: number) => `buf${i}`;
export const actualKey = (i: number) => `actual${i}`;

/** 날짜 오름차순의 API 응답 → 두 차트가 같이 쓰는 줄. 빈 응답이면 null. */
export function buildChartSeries(days: RecommendationHistoryDay[]): ChartSeries | null {
  if (days.length === 0) return null;
  const sorted = [...days].sort((a, b) => a.date.localeCompare(b.date));
  const versions = [
    ...new Set(sorted.flatMap((d) => d.recommendations.map((r) => r.modelVersion))),
  ].sort(compareVersions);
  const maxTrips = Math.max(0, ...sorted.map((d) => d.trips.length));
  const byDate = new Map(sorted.map((d) => [d.date, d]));

  const rows: ChartRow[] = [];
  const times: number[] = [];
  const buffers: number[] = [];
  const last = sorted[sorted.length - 1]!.date;
  for (let date = sorted[0]!.date; date <= last; date = addDays(date, 1)) {
    const day = byDate.get(date);
    const row: ChartRow = { date, hasData: day !== undefined };
    versions.forEach((v, i) => {
      const rec = day?.recommendations.find((r) => r.modelVersion === v);
      const t = rec ? kstMinutesOfDay(rec.recommendedLeaveHomeAt, date) : null;
      const b = rec ? rec.bufferSeconds / 60 : null;
      row[recKey(i)] = t;
      row[bufKey(i)] = b;
      if (t !== null) times.push(t);
      if (b !== null) buffers.push(b);
    });
    for (let i = 0; i < maxTrips; i++) {
      const left = day?.trips[i]?.leftHomeAt;
      const t = left ? kstMinutesOfDay(left, date) : null;
      row[actualKey(i)] = t;
      if (t !== null) times.push(t);
    }
    rows.push(row);
  }

  const time = timeAxis(times);
  const buffer = bufferAxis(buffers);
  return {
    versions,
    maxTrips,
    rows,
    timeDomain: time.domain,
    timeTicks: time.ticks,
    bufferDomain: buffer.domain,
    bufferTicks: buffer.ticks,
  };
}

const TIME_STEPS = [5, 10, 15, 30, 60, 120, 180];

/** 하루 중 시각(분) 축: 깔끔한 간격(5·10·15·30분, 1·2·3시간)으로 눈금 4~7개. */
export function timeAxis(values: number[]): { domain: [number, number]; ticks: number[] } {
  if (values.length === 0) return { domain: [420, 480], ticks: [420, 435, 450, 465, 480] };
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = Math.max(hi - lo, 10);
  const step = TIME_STEPS.find((s) => span / s <= 5) ?? 180;
  const start = Math.floor((lo - step / 4) / step) * step;
  const end = Math.ceil((hi + step / 4) / step) * step;
  const ticks: number[] = [];
  for (let t = start; t <= end; t += step) ticks.push(t);
  return { domain: [start, end], ticks };
}

const BUFFER_STEPS = [1, 2, 5, 10, 15, 30, 60];

/** buffer(분) 축: 0부터, 깔끔한 간격으로 눈금 4~6개. */
export function bufferAxis(values: number[]): { domain: [number, number]; ticks: number[] } {
  const hi = Math.max(0, ...values);
  const step = BUFFER_STEPS.find((s) => Math.max(hi, 1) / s <= 5) ?? 60;
  const end = Math.max(step, Math.ceil(hi / step) * step);
  const ticks: number[] = [];
  for (let t = 0; t <= end; t += step) ticks.push(t);
  return { domain: [0, end], ticks };
}

/** 계열의 마지막 값이 있는 줄 번호(직접 라벨을 그 점에 단다). 값이 없으면 -1. */
export function lastIndexWithValue(rows: ChartRow[], key: string): number {
  for (let i = rows.length - 1; i >= 0; i--) {
    if (typeof rows[i]![key] === "number") return i;
  }
  return -1;
}

/** 실제 출발 계열들(`actual0`…) 중 마지막 값이 있는 (줄, 키). 직접 라벨은 그 한 점에만 단다. */
export function lastActualPoint(series: ChartSeries): { index: number; key: string } | null {
  let best: { index: number; key: string } | null = null;
  for (let i = 0; i < series.maxTrips; i++) {
    const index = lastIndexWithValue(series.rows, actualKey(i));
    if (index >= 0 && (!best || index > best.index)) best = { index, key: actualKey(i) };
  }
  return best;
}

/**
 * 직접 라벨(계열 이름)을 달 계열. 마지막 값끼리 축 범위의 `minGap` 비율보다 가까우면 라벨이 겹치므로 그 계열들은
 * 라벨을 달지 않고 범례·툴팁에 맡긴다 (위아래로 밀어 떼어 놓으면 선에서 떨어져 오히려 헷갈린다). 끝점이 서로
 * 이틀 넘게 떨어져 있으면 가로로 비켜 있어 겹치지 않는다.
 */
export function labelableEnds(
  ends: { key: string; index: number; value: number }[],
  domain: [number, number],
  minGap = 0.08,
): Set<string> {
  const span = Math.max(domain[1] - domain[0], 1e-9);
  const ok = new Set<string>();
  for (const a of ends) {
    const clear = (b: (typeof ends)[number]) =>
      b === a || Math.abs(a.index - b.index) > 2 || Math.abs(a.value - b.value) / span >= minGap;
    if (ends.every(clear)) ok.add(a.key);
  }
  return ok;
}

/** `keys` 계열마다 마지막 값이 있는 (줄, 값). 값이 없는 계열은 빠진다. */
export function seriesEnds(rows: ChartRow[], keys: string[]) {
  return keys.flatMap((key) => {
    const index = lastIndexWithValue(rows, key);
    return index < 0 ? [] : [{ key, index, value: rows[index]![key] as number }];
  });
}

// --- 표 ---

export interface TableRow {
  /** React key. */
  key: string;
  date: string;
  /** 그날 trip 중 몇 번째인가 (0부터). trip이 없는 날은 0. 같은 날 둘째 줄부터 날짜·추천 칸을 비운다. */
  tripIndex: number;
  recommendations: DepartureRecommendation[];
  /** 지각 판정 기준 목표 도착 ([referenceRecommendation]). 추천이 없으면 null. */
  targetArrivalAt: string | null;
  trip: RecommendationHistoryTrip | null;
  /** 버전마다 실제 출발 − 추천 출발(초). trip이 없거나 집 나선 시각이 없으면 sec가 null. */
  leaveDiffs: { modelVersion: string; sec: number | null }[];
  arrival: Arrival | null;
}

/** 표: 날짜마다, trip이 여럿이면 trip마다 한 줄. trip이 없는 날도 추천만으로 한 줄. 날짜 오름차순. */
export function buildTableRows(days: RecommendationHistoryDay[]): TableRow[] {
  const rows: TableRow[] = [];
  for (const day of [...days].sort((a, b) => a.date.localeCompare(b.date))) {
    const recommendations = [...day.recommendations].sort((a, b) =>
      compareVersions(a.modelVersion, b.modelVersion),
    );
    const targetArrivalAt = referenceRecommendation(recommendations)?.targetArrivalAt ?? null;
    const trips: (RecommendationHistoryTrip | null)[] = day.trips.length > 0 ? day.trips : [null];
    trips.forEach((trip, tripIndex) => {
      rows.push({
        key: `${day.date}-${trip?.tripId ?? "none"}`,
        date: day.date,
        tripIndex,
        recommendations,
        targetArrivalAt,
        trip,
        leaveDiffs: recommendations.map((r) => ({
          modelVersion: r.modelVersion,
          sec: trip ? leaveDiffSec(trip, r) : null,
        })),
        arrival: trip ? arrivalAgainstTarget(trip, targetArrivalAt) : null,
      });
    });
  }
  return rows;
}
