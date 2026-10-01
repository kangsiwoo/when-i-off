import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  DepartureRecommendation,
  RecommendationHistoryDay,
  RecommendationHistoryTrip,
} from "../api/client";
import {
  actualKey,
  arrivalAgainstTarget,
  bufKey,
  bufferAxis,
  buildChartSeries,
  buildTableRows,
  formatMinuteOfDay,
  formatSignedMinutes,
  labelableEnds,
  lastActualPoint,
  leaveDiffSec,
  recKey,
  referenceRecommendation,
  shortDate,
  timeAxis,
  versionSlots,
} from "./history";

function rec(overrides: Partial<DepartureRecommendation> = {}): DepartureRecommendation {
  return {
    recommendedLeaveHomeAt: "2026-09-20T22:24:00Z", // KST 07:24
    targetArrivalAt: "2026-09-21T00:00:00Z", // KST 09:00
    catchProbability: 0.9,
    bufferSeconds: 600,
    modelVersion: "v1",
    computedAt: "2026-09-20T21:00:00Z",
    ...overrides,
  };
}

function trip(overrides: Partial<RecommendationHistoryTrip> = {}): RecommendationHistoryTrip {
  return {
    tripId: 1,
    leftHomeAt: "2026-09-20T22:27:30Z",
    arrivedDestinationAt: "2026-09-20T23:58:00Z",
    allLegsCaught: true,
    missedCount: 0,
    ...overrides,
  };
}

// 브라우저 시간대가 KST가 아니어도 같은 값이 나와야 한다 (kst.test.ts와 같은 방식).
describe.each(["UTC", "America/Los_Angeles", "Asia/Seoul"])("with TZ=%s", (tz) => {
  beforeEach(() => vi.stubEnv("TZ", tz));
  afterEach(() => vi.unstubAllEnvs());

  it("leave difference is actual minus recommended", () => {
    expect(leaveDiffSec(trip(), rec())).toBe(210);
    expect(leaveDiffSec(trip({ leftHomeAt: "2026-09-20T22:20:00Z" }), rec())).toBe(-240);
    expect(leaveDiffSec(trip({ leftHomeAt: null }), rec())).toBeNull();
    expect(leaveDiffSec(trip({ leftHomeAt: undefined }), rec())).toBeNull();
  });

  it("late means arriving after the target by any amount; exactly on time is not late", () => {
    expect(arrivalAgainstTarget(trip(), "2026-09-21T00:00:00Z")).toEqual({
      diffSec: -120,
      late: false,
    });
    expect(
      arrivalAgainstTarget(
        trip({ arrivedDestinationAt: "2026-09-21T00:00:00Z" }),
        "2026-09-21T00:00:00Z",
      ),
    ).toEqual({ diffSec: 0, late: false });
    expect(
      arrivalAgainstTarget(
        trip({ arrivedDestinationAt: "2026-09-21T00:00:20Z" }),
        "2026-09-21T00:00:00Z",
      ),
    ).toEqual({ diffSec: 20, late: true });
    expect(
      arrivalAgainstTarget(trip({ arrivedDestinationAt: null }), "2026-09-21T00:00:00Z"),
    ).toBeNull();
    expect(arrivalAgainstTarget(trip(), null)).toBeNull();
  });

  it("puts recommended and actual departures on KST minutes of each date", () => {
    const days: RecommendationHistoryDay[] = [
      {
        date: "2026-09-21",
        recommendations: [
          rec({
            modelVersion: "v2",
            recommendedLeaveHomeAt: "2026-09-20T22:30:00Z",
            bufferSeconds: 420,
          }),
          rec(),
        ],
        trips: [trip(), trip({ tripId: 2, leftHomeAt: "2026-09-21T09:05:00Z" })],
      },
      // 9/22는 기록이 없다 → 빈 칸으로 채운다.
      {
        date: "2026-09-23",
        recommendations: [],
        trips: [trip({ tripId: 3, leftHomeAt: "2026-09-22T22:31:00Z" })],
      },
    ];
    const s = buildChartSeries(days)!;
    expect(s.versions).toEqual(["v1", "v2"]);
    expect(s.maxTrips).toBe(2);
    expect(s.rows.map((r) => r.date)).toEqual(["2026-09-21", "2026-09-22", "2026-09-23"]);
    expect(s.rows.map((r) => r.hasData)).toEqual([true, false, true]);

    const [first, gap, third] = s.rows;
    expect(first![recKey(0)]).toBe(7 * 60 + 24);
    expect(first![recKey(1)]).toBe(7 * 60 + 30);
    expect(first![bufKey(0)]).toBe(10);
    expect(first![bufKey(1)]).toBe(7);
    expect(first![actualKey(0)]).toBe(7 * 60 + 27.5);
    expect(first![actualKey(1)]).toBe(18 * 60 + 5);
    expect(gap![recKey(0)]).toBeNull();
    expect(gap![actualKey(0)]).toBeNull();
    expect(third![recKey(0)]).toBeNull();
    expect(third![actualKey(0)]).toBe(7 * 60 + 31);
    expect(third![actualKey(1)]).toBeNull();

    expect(lastActualPoint(s)).toEqual({ index: 2, key: actualKey(0) });
    // 축은 모든 값을 덮는다.
    expect(s.timeDomain[0]).toBeLessThanOrEqual(7 * 60 + 24);
    expect(s.timeDomain[1]).toBeGreaterThanOrEqual(18 * 60 + 5);
  });

  it("table compares each version and judges lateness against the latest computed target", () => {
    const rows = buildTableRows([
      {
        date: "2026-09-22",
        recommendations: [
          rec({
            recommendedLeaveHomeAt: "2026-09-21T22:24:00Z",
            targetArrivalAt: "2026-09-22T00:00:00Z",
          }),
        ],
        trips: [],
      },
      {
        date: "2026-09-21",
        recommendations: [
          // v2가 더 늦게 계산됐고 목표가 30분 늦다 → 지각 판정은 v2의 목표 기준.
          rec({
            modelVersion: "v2",
            recommendedLeaveHomeAt: "2026-09-20T22:50:00Z",
            targetArrivalAt: "2026-09-21T00:30:00Z",
            computedAt: "2026-09-20T21:30:00Z",
          }),
          rec(),
        ],
        trips: [
          trip({ arrivedDestinationAt: "2026-09-21T00:10:00Z" }),
          trip({ tripId: 2, leftHomeAt: null }),
        ],
      },
    ]);
    expect(rows.map((r) => [r.date, r.tripIndex, r.trip?.tripId ?? null])).toEqual([
      ["2026-09-21", 0, 1],
      ["2026-09-21", 1, 2],
      ["2026-09-22", 0, null],
    ]);
    const [a, b, c] = rows;
    expect(a!.recommendations.map((r) => r.modelVersion)).toEqual(["v1", "v2"]);
    expect(a!.targetArrivalAt).toBe("2026-09-21T00:30:00Z");
    expect(a!.leaveDiffs).toEqual([
      { modelVersion: "v1", sec: 210 },
      { modelVersion: "v2", sec: -1350 },
    ]);
    expect(a!.arrival).toEqual({ diffSec: -1200, late: false });
    expect(b!.leaveDiffs.map((d) => d.sec)).toEqual([null, null]);
    expect(c!.trip).toBeNull();
    expect(c!.arrival).toBeNull();
    expect(c!.leaveDiffs).toEqual([{ modelVersion: "v1", sec: null }]);
  });
});

describe("formatting", () => {
  it("signed minutes never hide a sub-minute difference as zero", () => {
    expect(formatSignedMinutes(0)).toBe("±0분");
    expect(formatSignedMinutes(210)).toBe("+4분");
    expect(formatSignedMinutes(-1350)).toBe("−23분");
    expect(formatSignedMinutes(20)).toBe("+1분 미만");
    expect(formatSignedMinutes(-29)).toBe("−1분 미만");
  });

  it("minutes of day wrap past midnight", () => {
    expect(formatMinuteOfDay(7 * 60 + 5)).toBe("07:05");
    expect(formatMinuteOfDay(24 * 60 + 10)).toBe("00:10");
    expect(formatMinuteOfDay(-30)).toBe("23:30");
    expect(shortDate("2026-09-05")).toBe("9/5");
  });
});

describe("axes", () => {
  it("time axis uses clean steps and covers the values", () => {
    const { domain, ticks } = timeAxis([7 * 60 + 24, 7 * 60 + 41]);
    expect(ticks.every((t) => t % 5 === 0)).toBe(true);
    expect(domain[0]).toBeLessThanOrEqual(7 * 60 + 24);
    expect(domain[1]).toBeGreaterThanOrEqual(7 * 60 + 41);
    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(ticks.length).toBeLessThanOrEqual(8);
    expect(timeAxis([]).ticks.length).toBeGreaterThan(0);
  });

  it("buffer axis starts at zero", () => {
    expect(bufferAxis([15, 9, 4])).toEqual({ domain: [0, 15], ticks: [0, 5, 10, 15] });
    expect(bufferAxis([])).toEqual({ domain: [0, 1], ticks: [0, 1] });
  });
});

describe("version colors", () => {
  it("follow the version number, not how many versions are in view", () => {
    expect(versionSlots(["v1", "v2"])).toEqual(
      new Map([
        ["v1", "series-2"],
        ["v2", "series-3"],
      ]),
    );
    // v1이 기간 밖으로 빠져도 v2의 색은 그대로다.
    expect(versionSlots(["v2"]).get("v2")).toBe("series-3");
    expect(versionSlots(["v2", "v3"])).toEqual(
      new Map([
        ["v3", "series-2"],
        ["v2", "series-3"],
      ]),
    );
  });

  it("fold older versions competing for a slot and unnumbered versions to gray", () => {
    const slots = versionSlots(["v1", "v2", "v3", "exp"]);
    expect(slots.get("v3")).toBe("series-2");
    expect(slots.get("v2")).toBe("series-3");
    expect(slots.get("v1")).toBe("other");
    expect(slots.get("exp")).toBe("other");
  });

  it("reference recommendation is the latest computed, later version on ties", () => {
    const a = rec({ modelVersion: "v1", computedAt: "2026-09-20T21:00:00Z" });
    const b = rec({ modelVersion: "v2", computedAt: "2026-09-20T21:00:00Z" });
    const c = rec({ modelVersion: "v1", computedAt: "2026-09-20T22:00:00Z" });
    expect(referenceRecommendation([a, b])).toBe(b);
    expect(referenceRecommendation([b, c])).toBe(c);
    expect(referenceRecommendation([])).toBeNull();
  });
});

describe("direct labels", () => {
  it("drop labels of series whose ends would collide and keep the rest", () => {
    const ends = [
      { key: "rec0", index: 16, value: 480 },
      { key: "rec1", index: 16, value: 478 },
      { key: "actual0", index: 16, value: 438 },
    ];
    expect(labelableEnds(ends, [430, 490])).toEqual(new Set(["actual0"]));
    // 끝이 가로로 떨어져 있으면 값이 가까워도 둔다.
    expect(labelableEnds([ends[0]!, { ...ends[1]!, index: 10 }], [430, 490])).toEqual(
      new Set(["rec0", "rec1"]),
    );
  });
});
