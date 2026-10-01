import { describe, expect, it } from "vitest";
import { caughtAttempt, trip } from "../test/fixtures";
import { attemptForm, attemptPatch, tripPatch, tripTimesForm } from "./corrections";

describe("tripPatch", () => {
  it("prefills KST and sends only changed fields as UTC", () => {
    const form = tripTimesForm(trip);
    expect(form).toEqual({
      leftHomeAt: "2026-09-11T07:30:00",
      arrivedDestinationAt: "2026-09-11T08:25:30",
    });
    expect(tripPatch(trip, form)).toEqual({ ok: true, patch: {} });
    expect(tripPatch(trip, { ...form, arrivedDestinationAt: "2026-09-11T08:20:00" })).toEqual({
      ok: true,
      patch: { arrivedDestinationAt: "2026-09-10T23:20:00Z" },
    });
  });

  it("treats a browser-shortened value (no seconds) of the same time as unchanged", () => {
    expect(
      tripPatch(trip, {
        leftHomeAt: "2026-09-11T07:30",
        arrivedDestinationAt: "2026-09-11T08:25:30",
      }),
    ).toEqual({ ok: true, patch: {} });
  });

  it("fills a time that was empty", () => {
    expect(
      tripPatch(
        { ...trip, arrivedDestinationAt: null },
        {
          leftHomeAt: "2026-09-11T07:30:00",
          arrivedDestinationAt: "2026-09-11T08:26",
        },
      ),
    ).toEqual({ ok: true, patch: { arrivedDestinationAt: "2026-09-10T23:26:00Z" } });
  });

  it("refuses to clear a recorded time (the API cannot)", () => {
    const result = tripPatch(trip, { ...tripTimesForm(trip), leftHomeAt: "" });
    expect(result).toEqual({ ok: false, message: "집 나섬: 기록된 시각은 지울 수 없습니다." });
  });
});

describe("attemptPatch", () => {
  it("sends changed times, result and notes", () => {
    const form = attemptForm(caughtAttempt);
    expect(form).toEqual({
      vehicleActualDepartureAt: "2026-09-11T07:57:45",
      alightedAt: "2026-09-11T08:18:10",
      result: "CAUGHT",
      notes: "지하에서 하차가 늦게 잡힘",
    });
    expect(attemptPatch(caughtAttempt, form)).toEqual({ ok: true, patch: {} });
    expect(
      attemptPatch(caughtAttempt, {
        ...form,
        alightedAt: "2026-09-11T08:16:00",
        result: "UNKNOWN",
        notes: "",
      }),
    ).toEqual({
      ok: true,
      patch: { alightedAt: "2026-09-10T23:16:00Z", result: "UNKNOWN", notes: "" },
    });
  });

  it("refuses to clear a recorded departure", () => {
    const result = attemptPatch(caughtAttempt, {
      ...attemptForm(caughtAttempt),
      vehicleActualDepartureAt: "",
    });
    expect(result).toEqual({ ok: false, message: "출발: 기록된 시각은 지울 수 없습니다." });
  });
});
