import type { RecommendationEvaluationSummary, RecommendationHistoryDay } from "../api/client";
import { compareVersions, formatSignedMinutes, versionSlots, type SeriesSlot } from "./history";

// 버전별 성과 요약(#79)의 표시값. 숫자 자체(n, 지각률, 평균)는 backend가 analytics `summarize()`와 같은 정의로
// 내 주고, 여기서는 고르고 다듬기만 한다 — 화면과 CLI 출력의 숫자가 어긋나지 않게.

/** 이보다 평가가 적으면 "표본 적음"을 붙인다. 하루 1행이라 일주일(평일 5일) 미만이다. */
export const LOW_SAMPLE_N = 5;

export interface StatTile {
  key: "n" | "late" | "departure" | "wait" | "caught";
  label: string;
  /** 큰 숫자. 값이 없으면 `—`. */
  value: string;
  /** 숫자 아래 한 줄 (분모, 부호의 뜻 등). */
  detail: string;
}

export interface VersionSummaryCard {
  modelVersion: string;
  /** 차트와 같은 버전 색 자리 ([versionSlots]). */
  slot: SeriesSlot;
  n: number;
  lowSample: boolean;
  tiles: StatTile[];
}

const DASH = "—";

/** 0~1 비율 → `33%`. 없으면 `—`. */
export function formatRate(rate: number | null | undefined): string {
  return rate === null || rate === undefined ? DASH : `${Math.round(rate * 100)}%`;
}

/** 평균 출발 차이(초, 소수 가능) → `+3분` / `−36분` / `±0분`. 없으면 `—`. */
export function formatMeanDiff(sec: number | null | undefined): string {
  return sec === null || sec === undefined ? DASH : formatSignedMinutes(Math.round(sec));
}

/** 대기(초) → `8분 52초`, 1분 미만은 `45초`. 정류장 대기는 몇 분 단위라 초까지 보여도 길지 않다. 없으면 `—`. */
export function formatWait(sec: number | null | undefined): string {
  if (sec === null || sec === undefined) return DASH;
  const total = Math.max(0, Math.round(sec));
  const m = Math.floor(total / 60);
  const s = total % 60;
  if (m === 0) return `${s}초`;
  return s === 0 ? `${m}분` : `${m}분 ${s}초`;
}

/**
 * 요약 → 버전별 카드, 버전 순(`v2` < `v10`). 버전 색은 차트와 같아야 하므로 `chartVersions`(차트가 그리는 버전,
 * 즉 추천 이력에 나온 버전)와 요약의 버전을 합쳐 [versionSlots]에 넘긴다. 평가는 기간 안의 추천에서만 나오므로
 * 보통 요약의 버전은 차트 버전에 다 들어 있다.
 */
export function buildSummaryCards(
  summaries: RecommendationEvaluationSummary[],
  chartVersions: string[] = [],
): VersionSummaryCard[] {
  const sorted = [...summaries].sort((a, b) => compareVersions(a.modelVersion, b.modelVersion));
  const slots = versionSlots([
    ...new Set([...chartVersions, ...sorted.map((s) => s.modelVersion)]),
  ]);
  return sorted.map((s) => ({
    modelVersion: s.modelVersion,
    slot: slots.get(s.modelVersion) ?? "other",
    n: s.n,
    lowSample: s.n < LOW_SAMPLE_N,
    tiles: [
      { key: "n", label: "평가한 날", value: `${s.n}일`, detail: "추천과 이동 기록이 같은 날" },
      {
        key: "late",
        label: "지각률",
        value: formatRate(s.lateRate),
        detail:
          s.withArrival === 0
            ? "도착 기록 없음"
            : `${s.lateCount}/${s.withArrival}일 (도착 기록 있는 날)`,
      },
      {
        key: "departure",
        label: "평균 출발 차이",
        value: formatMeanDiff(s.meanDepartureDiffSec),
        detail: "추천 대비, +면 늦게 나섬",
      },
      {
        key: "wait",
        label: "평균 정류장 대기",
        value: formatWait(s.meanStopWaitSec),
        detail: "탄 구간의 도착→탄 차 출발",
      },
      {
        key: "caught",
        label: "전 구간 탑승률",
        value: formatRate(s.allLegsCaughtRate),
        detail: `${s.allLegsCaughtCount}/${s.n}일`,
      },
    ],
  }));
}

/**
 * 평가가 없을 때 이유. 같은 날 추천과 trip이 둘 다 있는 날이 있으면 `evaluate`가 아직 안 돈 것이고,
 * 없으면 평가할 거리가 없는 것이다 (analytics는 trip이 없는 날에 행을 만들지 않는다).
 */
export function missingEvaluationReason(
  days: RecommendationHistoryDay[],
): "not-evaluated" | "nothing-to-evaluate" {
  return days.some((d) => d.recommendations.length > 0 && d.trips.length > 0)
    ? "not-evaluated"
    : "nothing-to-evaluate";
}

/** 차트가 그리는 버전 (추천 이력에 나온 버전). 요약 카드의 색을 차트와 맞출 때 쓴다. */
export function historyVersions(days: RecommendationHistoryDay[]): string[] {
  return [...new Set(days.flatMap((d) => d.recommendations.map((r) => r.modelVersion)))];
}
