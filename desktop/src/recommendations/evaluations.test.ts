import { describe, expect, it } from "vitest";
import type { RecommendationEvaluationSummary, RecommendationHistoryDay } from "../api/client";
import {
  buildSummaryCards,
  formatMeanDiff,
  formatRate,
  formatWait,
  historyVersions,
  LOW_SAMPLE_N,
  missingEvaluationReason,
} from "./evaluations";

function summary(
  overrides: Partial<RecommendationEvaluationSummary> = {},
): RecommendationEvaluationSummary {
  return {
    modelVersion: "v1",
    n: 5,
    lateCount: 1,
    withArrival: 4,
    lateRate: 0.25,
    meanDepartureDiffSec: 90,
    meanStopWaitSec: 300,
    allLegsCaughtCount: 5,
    allLegsCaughtRate: 1,
    ...overrides,
  };
}

function day(date: string, versions: string[], trips: number): RecommendationHistoryDay {
  return {
    date,
    recommendations: versions.map((modelVersion) => ({
      recommendedLeaveHomeAt: `${date}T22:24:00Z`,
      targetArrivalAt: `${date}T23:59:00Z`,
      catchProbability: 0.9,
      bufferSeconds: 600,
      modelVersion,
      computedAt: `${date}T21:00:00Z`,
    })),
    trips: Array.from({ length: trips }, (_, i) => ({
      tripId: i + 1,
      allLegsCaught: true,
      missedCount: 0,
    })),
  };
}

describe("formatting", () => {
  it("rounds rates to whole percents and shows a dash when there is no denominator", () => {
    expect(formatRate(0)).toBe("0%");
    expect(formatRate(2 / 3)).toBe("67%");
    expect(formatRate(1)).toBe("100%");
    expect(formatRate(null)).toBe("—");
    expect(formatRate(undefined)).toBe("—");
  });

  it("signs the mean departure difference in minutes", () => {
    expect(formatMeanDiff(-2177.4)).toBe("−36분");
    expect(formatMeanDiff(150)).toBe("+3분");
    expect(formatMeanDiff(0.4)).toBe("±0분");
    expect(formatMeanDiff(20)).toBe("+1분 미만");
    expect(formatMeanDiff(undefined)).toBe("—");
  });

  it("shows waits in minutes and seconds", () => {
    expect(formatWait(532)).toBe("8분 52초");
    expect(formatWait(531.6)).toBe("8분 52초");
    expect(formatWait(45)).toBe("45초");
    expect(formatWait(600)).toBe("10분");
    expect(formatWait(0)).toBe("0초");
    expect(formatWait(null)).toBe("—");
  });
});

describe("buildSummaryCards", () => {
  it("orders versions numerically and fills five tiles", () => {
    const cards = buildSummaryCards([
      summary({ modelVersion: "v10" }),
      summary({ modelVersion: "v2" }),
    ]);
    expect(cards.map((c) => c.modelVersion)).toEqual(["v2", "v10"]);
    expect(cards[0]!.tiles.map((t) => [t.label, t.value, t.detail])).toEqual([
      ["평가한 날", "5일", "추천과 이동 기록이 같은 날"],
      ["지각률", "25%", "1/4일 (도착 기록 있는 날)"],
      ["평균 출발 차이", "+2분", "추천 대비, +면 늦게 나섬"],
      ["평균 정류장 대기", "5분", "탄 구간의 도착→탄 차 출발"],
      ["전 구간 탑승률", "100%", "5/5일"],
    ]);
  });

  it(`marks fewer than ${LOW_SAMPLE_N} evaluations as a small sample`, () => {
    const cards = buildSummaryCards([
      summary({ modelVersion: "v1", n: LOW_SAMPLE_N - 1 }),
      summary({ modelVersion: "v2", n: LOW_SAMPLE_N }),
    ]);
    expect(cards.map((c) => c.lowSample)).toEqual([true, false]);
  });

  it("leaves missing values as dashes instead of zeros", () => {
    const [card] = buildSummaryCards([
      summary({
        n: 1,
        lateCount: 0,
        withArrival: 0,
        lateRate: undefined,
        meanDepartureDiffSec: undefined,
        meanStopWaitSec: null,
        allLegsCaughtCount: 0,
        allLegsCaughtRate: 0,
      }),
    ]);
    const byKey = Object.fromEntries(card!.tiles.map((t) => [t.key, t]));
    expect(byKey.late).toMatchObject({ value: "—", detail: "도착 기록 없음" });
    expect(byKey.departure!.value).toBe("—");
    expect(byKey.wait!.value).toBe("—");
    expect(byKey.caught).toMatchObject({ value: "0%", detail: "0/1일" });
  });

  it("colors versions by number like the charts, using the chart's versions", () => {
    // 차트에 v1·v2·v3이 보이면 v3이 2번 자리를 가져가 v1은 회색이다. 요약에 v1만 있어도 같은 색이어야 한다.
    expect(buildSummaryCards([summary({ modelVersion: "v1" })], ["v1", "v2", "v3"])[0]!.slot).toBe(
      "other",
    );
    const cards = buildSummaryCards([
      summary({ modelVersion: "v1" }),
      summary({ modelVersion: "v2" }),
    ]);
    expect(cards.map((c) => c.slot)).toEqual(["series-2", "series-3"]);
    expect(buildSummaryCards([summary({ modelVersion: "baseline" })])[0]!.slot).toBe("other");
  });
});

describe("missingEvaluationReason", () => {
  it("asks for evaluate only when some day has both a recommendation and a trip", () => {
    expect(missingEvaluationReason([day("2026-09-21", ["v1"], 1)])).toBe("not-evaluated");
    expect(missingEvaluationReason([day("2026-09-21", ["v1"], 0), day("2026-09-22", [], 2)])).toBe(
      "nothing-to-evaluate",
    );
    expect(missingEvaluationReason([])).toBe("nothing-to-evaluate");
  });
});

describe("historyVersions", () => {
  it("collects each version once", () => {
    expect(
      historyVersions([day("2026-09-21", ["v1", "v2"], 0), day("2026-09-22", ["v2"], 0)]),
    ).toEqual(["v1", "v2"]);
  });
});
