import { describe, expect, it } from "vitest";
import type { PredictionCalibrationRow, WalkingProfile } from "../api/client";
import {
  DEFAULT_PREDICTION_ERROR,
  DEFAULT_WALKING_SPEED,
  isLowSample,
  poolStats,
  predictionFallback,
  resolveWalkingSpeed,
  travelTimeFallback,
} from "./chain";

const profile = (
  avgSpeedMps: number,
  stddevSpeedMps: number,
  sampleCount: number,
): WalkingProfile => ({
  avgSpeedMps,
  stddevSpeedMps,
  sampleCount,
  updatedAt: "2026-09-30T18:00:00Z",
});

const prediction = (
  biasSec: number,
  stddevSec: number,
  sampleCount: number,
  timeBandStart = "07:30",
): PredictionCalibrationRow => ({
  dayType: "WEEKDAY",
  timeBandStart,
  timeBandEnd: "08:00",
  biasSec,
  stddevSec,
  sampleCount,
  updatedAt: "2026-09-30T18:00:00Z",
});

describe("isLowSample", () => {
  it("is strict: exactly minSamples is enough", () => {
    expect(isLowSample(4, 5)).toBe(true);
    expect(isLowSample(5, 5)).toBe(false);
  });
});

describe("resolveWalkingSpeed", () => {
  it("uses the leg row when it has enough samples", () => {
    expect(resolveWalkingSpeed(profile(1.4, 0.1, 5), profile(1.25, 0.2, 30), 5)).toEqual({
      value: { mean: 1.4, stddev: 0.1 },
      source: "calibrated",
      sampleCount: 5,
    });
  });

  it("falls back to the global row when the leg row is missing or too small", () => {
    const global = profile(1.25, 0.2, 30);
    for (const leg of [null, undefined, profile(1.4, 0.1, 4)]) {
      expect(resolveWalkingSpeed(leg, global, 5)).toEqual({
        value: { mean: 1.25, stddev: 0.2 },
        source: "inherited",
        sampleCount: 30,
      });
    }
  });

  it("falls back to the default 1.2 ± 0.15 m/s when neither row is usable", () => {
    expect(resolveWalkingSpeed(profile(1.4, 0.1, 4), profile(1.25, 0.2, 2), 5)).toEqual({
      value: DEFAULT_WALKING_SPEED,
      source: "default",
      sampleCount: 0,
    });
    expect(DEFAULT_WALKING_SPEED).toEqual({ mean: 1.2, stddev: 0.15 });
  });

  it("does not use a σ below the floor", () => {
    expect(resolveWalkingSpeed(profile(1.4, 0.01, 9), null, 5).value.stddev).toBe(0.05);
  });
});

describe("poolStats", () => {
  it("matches the stddev of all samples pooled together, not the mean of σs", () => {
    // [1, 2, 3] → mean 2, s = 1 / [5, 7] → mean 6, s = √2. 전체 [1, 2, 3, 5, 7] → mean 3.6, s² = 5.8
    const pooled = poolStats([
      { count: 3, mean: 2, stddev: 1 },
      { count: 2, mean: 6, stddev: Math.SQRT2 },
    ]);
    expect(pooled?.count).toBe(5);
    expect(pooled?.mean).toBeCloseTo(3.6);
    expect(pooled?.stddev).toBeCloseTo(Math.sqrt(5.8));
  });

  it("is null without samples and keeps σ of a single sample", () => {
    expect(poolStats([])).toBeNull();
    expect(poolStats([{ count: 0, mean: 1, stddev: 1 }])).toBeNull();
    expect(poolStats([{ count: 1, mean: 10, stddev: 90 }])).toEqual({
      count: 1,
      mean: 10,
      stddev: 90,
    });
  });
});

describe("predictionFallback", () => {
  it("pools all rows of the line and stop when together they have enough samples", () => {
    const used = predictionFallback([prediction(20, 30, 3), prediction(20, 30, 2, "08:00")], 5);
    expect(used.source).toBe("inherited");
    expect(used.sampleCount).toBe(5);
    expect(used.value.mean).toBeCloseTo(20);
  });

  it("uses the default 0 ± 90 s otherwise, and floors σ at 15 s", () => {
    expect(predictionFallback([prediction(20, 30, 4)], 5)).toEqual({
      value: DEFAULT_PREDICTION_ERROR,
      source: "default",
      sampleCount: 0,
    });
    expect(predictionFallback([prediction(20, 5, 9)], 5).value.stddev).toBe(15);
  });
});

describe("travelTimeFallback", () => {
  it("defaults to planned travel time ± 15%", () => {
    expect(travelTimeFallback([], 5, 1200)).toEqual({
      value: { mean: 1200, stddev: 180 },
      source: "default",
      sampleCount: 0,
    });
    expect(travelTimeFallback([], 5, null)).toBeNull();
  });
});
