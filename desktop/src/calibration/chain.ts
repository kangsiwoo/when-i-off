import type {
  PredictionCalibrationRow,
  TravelTimeCalibrationRow,
  WalkingProfile,
} from "../api/client";

/*
 * recommend가 보정 테이블에서 값을 고르는 순서를 화면용으로 옮긴 것 (#60).
 * 원본: analytics/src/whenioff_analytics/model/lookup.py (조회 순서),
 *       analytics/src/whenioff_analytics/defaults.py (콜드스타트 기본값),
 *       analytics/src/whenioff_analytics/model/calibration.py (σ 하한).
 * 저쪽 값이 바뀌면 여기도 같이 바꾼다. 신뢰 기준(minSamples)은 API 응답으로 받는다.
 */

/** `defaults.WALKING_SPEED` — 도보 속도 1.2 m/s ± 0.15. */
export const DEFAULT_WALKING_SPEED: Normal = { mean: 1.2, stddev: 0.15 };
/** `defaults.PREDICTION_BIAS_SEC`, `defaults.PREDICTION_STDDEV_SEC` — 예측 오차 0초 ± 90초. */
export const DEFAULT_PREDICTION_ERROR: Normal = { mean: 0, stddev: 90 };
/** `defaults.TRAVEL_TIME_CV` — 차내 시간 기본값은 평균 `plannedTravelSec`, σ = 평균의 15%. */
export const TRAVEL_TIME_CV = 0.15;

/** `calibration.WALKING_STDDEV_FLOOR_MPS` 등 — 쓰이는 σ는 이 아래로 내려가지 않는다. */
export const WALKING_STDDEV_FLOOR_MPS = 0.05;
export const PREDICTION_STDDEV_FLOOR_SEC = 15;
export const TRAVEL_TIME_STDDEV_FLOOR_SEC = 15;

export interface Normal {
  mean: number;
  stddev: number;
}

/** 쓰인 값의 출처. `lookup.Provenance`의 CALIBRATED / INHERITED / DEFAULT. */
export type Source = "calibrated" | "inherited" | "default";

export interface Resolved {
  value: Normal;
  source: Source;
  /** 그 값 뒤의 샘플 수. 기본값이면 0. */
  sampleCount: number;
}

export const isLowSample = (sampleCount: number, minSamples: number) => sampleCount < minSamples;

/**
 * 도보 속도: 구간 행 → 사용자 전역 행 → 기본값 (`lookup.resolve_walking_speed`).
 * 샘플이 `minSamples` 미만인 행은 건너뛴다.
 */
export function resolveWalkingSpeed(
  legProfile: WalkingProfile | null | undefined,
  globalProfile: WalkingProfile | null | undefined,
  minSamples: number,
): Resolved {
  const levels: [WalkingProfile | null | undefined, Source][] = [
    [legProfile, "calibrated"],
    [globalProfile, "inherited"],
  ];
  for (const [profile, source] of levels) {
    if (profile && !isLowSample(profile.sampleCount, minSamples)) {
      return {
        value: {
          mean: profile.avgSpeedMps,
          stddev: Math.max(profile.stddevSpeedMps, WALKING_STDDEV_FLOOR_MPS),
        },
        source,
        sampleCount: profile.sampleCount,
      };
    }
  }
  return { value: DEFAULT_WALKING_SPEED, source: "default", sampleCount: 0 };
}

export interface SampleStats {
  count: number;
  mean: number;
  stddev: number;
}

/**
 * 그룹별 (n, 평균, 표본표준편차)를 한데 모은 값으로 합친다 (`lookup.pool_stats`).
 * σ의 평균이 아니라 그룹 안 제곱합 + 그룹 사이 제곱합을 N − 1로 나눈다.
 */
export function poolStats(groups: readonly SampleStats[]): SampleStats | null {
  const nonempty = groups.filter((g) => g.count > 0);
  const total = nonempty.reduce((sum, g) => sum + g.count, 0);
  if (total === 0) return null;
  const mean = nonempty.reduce((sum, g) => sum + g.count * g.mean, 0) / total;
  if (total === 1) return { count: 1, mean, stddev: nonempty[0]!.stddev };
  const within = nonempty.reduce((sum, g) => sum + (g.count - 1) * g.stddev ** 2, 0);
  const between = nonempty.reduce((sum, g) => sum + g.count * (g.mean - mean) ** 2, 0);
  return { count: total, mean, stddev: Math.sqrt((within + between) / (total - 1)) };
}

/**
 * 밴드 행이 쓰이지 못할 때(행이 없거나 샘플 부족) 내려가는 값: 같은 노선·정류장의 모든 행을 합친 값,
 * 그것도 부족하면 기본값 (`lookup._resolve`의 두·세 번째 단계).
 */
function fallback(
  stats: readonly SampleStats[],
  minSamples: number,
  floor: number,
  defaultValue: Normal,
): Resolved {
  const pooled = poolStats(stats);
  if (pooled && !isLowSample(pooled.count, minSamples)) {
    return {
      value: { mean: pooled.mean, stddev: Math.max(pooled.stddev, floor) },
      source: "inherited",
      sampleCount: pooled.count,
    };
  }
  return { value: defaultValue, source: "default", sampleCount: 0 };
}

/** 예측 오차 밴드 행이 부족할 때 쓰이는 값 (초, 평균 = bias). */
export function predictionFallback(
  rows: readonly PredictionCalibrationRow[],
  minSamples: number,
): Resolved {
  return fallback(
    rows.map((r) => ({ count: r.sampleCount, mean: r.biasSec, stddev: r.stddevSec })),
    minSamples,
    PREDICTION_STDDEV_FLOOR_SEC,
    DEFAULT_PREDICTION_ERROR,
  );
}

/** 차내 시간 밴드 행이 부족할 때 쓰이는 값 (초). 기본값은 `plannedTravelSec` ± 15%. */
export function travelTimeFallback(
  rows: readonly TravelTimeCalibrationRow[],
  minSamples: number,
  plannedTravelSec: number | null | undefined,
): Resolved | null {
  const planned = plannedTravelSec ?? null;
  const pooled = fallback(
    rows.map((r) => ({ count: r.sampleCount, mean: r.meanSec, stddev: r.stddevSec })),
    minSamples,
    TRAVEL_TIME_STDDEV_FLOOR_SEC,
    { mean: planned ?? 0, stddev: (planned ?? 0) * TRAVEL_TIME_CV },
  );
  // 기본값의 근거(planned_travel_sec)가 없으면 보여 줄 값도 없다. TRANSIT 구간은 DB 제약상 항상 있다.
  return pooled.source === "default" && planned === null ? null : pooled;
}
