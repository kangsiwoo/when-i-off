import { describe, expect, it } from "vitest";
import { caughtAttempt, detail, missedAttempt, trip } from "../test/fixtures";
import { buildTimeline, predictionErrorSec, summarizeTrip } from "./timeline";

describe("buildTimeline", () => {
  it("orders home → stop arrival → missed → caught departure → alight → destination", () => {
    const events = buildTimeline(trip, detail.legs);
    expect(events.map((e) => e.kind)).toEqual([
      "LEFT_HOME",
      "ARRIVED_STOP",
      "MISSED",
      "DEPARTED",
      "ALIGHTED",
      "ARRIVED_DESTINATION",
    ]);
    expect(events.map((e) => e.at)).toEqual([
      "2026-09-10T22:30:00Z",
      "2026-09-10T22:42:30Z", // 첫 시도의 정류장 도착
      "2026-09-10T22:43:20Z",
      "2026-09-10T22:57:45Z",
      "2026-09-10T23:18:10Z",
      "2026-09-10T23:25:30Z",
    ]);
    const stop = events[1]!;
    expect(stop.kind === "ARRIVED_STOP" && stop.leg.leg?.boardStop?.name).toBe("동탄");
  });

  it("keeps empty times in place and marks TRANSIT legs without attempts", () => {
    const events = buildTimeline(
      { ...trip, leftHomeAt: null, arrivedDestinationAt: null, boardingAttempts: [] },
      detail.legs,
    );
    expect(events.map((e) => [e.kind, e.at])).toEqual([
      ["LEFT_HOME", null],
      ["NO_ATTEMPT", null],
      ["ARRIVED_DESTINATION", null],
    ]);
  });

  it("works from attempts alone when the route detail is unavailable", () => {
    const unknown = {
      ...caughtAttempt,
      id: 103,
      routeLegId: 99,
      attemptSeq: 1,
      result: "UNKNOWN" as const,
    };
    const kinds = buildTimeline({
      ...trip,
      boardingAttempts: [...trip.boardingAttempts, unknown],
    }).map((e) => ("leg" in e ? `${e.kind}:${e.leg.id}` : e.kind));
    expect(kinds).toEqual([
      "LEFT_HOME",
      "ARRIVED_STOP:12",
      "MISSED:12",
      "DEPARTED:12",
      "ALIGHTED:12",
      "ARRIVED_STOP:99",
      "UNKNOWN:99",
      "ARRIVED_DESTINATION",
    ]);
  });
});

describe("predictionErrorSec", () => {
  it("is actual − predicted in seconds", () => {
    expect(predictionErrorSec(missedAttempt)).toBe(20);
    expect(predictionErrorSec(caughtAttempt)).toBe(-15);
  });

  it("is null when either side is missing", () => {
    expect(
      predictionErrorSec({ ...missedAttempt, vehicleScheduledOrPredictedAt: null }),
    ).toBeNull();
    expect(
      predictionErrorSec({ ...missedAttempt, vehicleActualDepartureAt: undefined }),
    ).toBeNull();
  });
});

describe("summarizeTrip", () => {
  it("counts missed vehicles and whether every TRANSIT leg was caught", () => {
    expect(summarizeTrip(trip, detail.legs)).toEqual({
      caughtLegs: 1,
      transitLegs: 1,
      allCaught: true,
      missed: 1,
      totalSec: 55 * 60 + 30,
    });
  });

  it("is not all-caught when a TRANSIT leg has only missed attempts or no attempt", () => {
    expect(
      summarizeTrip({ ...trip, boardingAttempts: [missedAttempt] }, detail.legs),
    ).toMatchObject({ caughtLegs: 0, transitLegs: 1, allCaught: false, missed: 1 });
    expect(summarizeTrip({ ...trip, boardingAttempts: [] }, detail.legs)).toMatchObject({
      transitLegs: 1,
      allCaught: false,
    });
  });

  it("has no total without both ends and no legs without attempts or route detail", () => {
    expect(summarizeTrip({ ...trip, arrivedDestinationAt: null, boardingAttempts: [] })).toEqual({
      caughtLegs: 0,
      transitLegs: 0,
      allCaught: false,
      missed: 0,
      totalSec: null,
    });
  });
});
