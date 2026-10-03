import { describe, expect, it } from "vitest";
import type { TrafficSignalCycle, TrafficSignalCycleRequest } from "../api/client";
import {
  draftsFromCycles,
  effectiveCycle,
  newCycleDraft,
  secondsOfDay,
  sortCycles,
  timeInputValue,
  toCycleRequests,
  validateCycleRequests,
  waitPreview,
} from "./cycleRules";

const req = (extra: Partial<TrafficSignalCycleRequest> = {}): TrafficSignalCycleRequest => ({
  dayType: "WEEKDAY",
  timeBandStart: "07:00",
  timeBandEnd: "10:00",
  cycleDurationSec: 150,
  redDurationSec: 110,
  ...extra,
});

let nextId = 0;
const row = (extra: Partial<TrafficSignalCycle>): TrafficSignalCycle => ({
  id: ++nextId,
  dayType: "WEEKDAY",
  timeBandStart: "07:00:00",
  timeBandEnd: "10:00:00",
  cycleDurationSec: 140,
  redDurationSec: 100,
  source: "PUBLIC_API",
  createdAt: "2026-10-03T00:00:00Z",
  ...extra,
});

describe("validateCycleRequests (backend TrafficSignalCycleService.validate)", () => {
  it("accepts valid rows, touching bands and the same band on another day type", () => {
    expect(
      validateCycleRequests([
        req({
          timeBandStart: "00:00",
          timeBandEnd: "07:00",
          cycleDurationSec: 30,
          redDurationSec: 29,
        }),
        req({ timeBandStart: "07:00", timeBandEnd: "23:59:59", cycleDurationSec: 300 }),
        req({ dayType: "SATURDAY", timeBandStart: "06:00", timeBandEnd: "08:00" }),
      ]),
    ).toEqual([]);
    expect(validateCycleRequests([])).toEqual([]);
  });

  it("keeps the cycle within 30–300 s", () => {
    expect(validateCycleRequests([req({ cycleDurationSec: 29, redDurationSec: 10 })])).toEqual([
      "1행: 주기는 30~300초 사이의 정수여야 합니다.",
    ]);
    expect(validateCycleRequests([req({ cycleDurationSec: 301 })])).toHaveLength(1);
    expect(validateCycleRequests([req({ cycleDurationSec: Number.NaN })])).toHaveLength(1);
  });

  it("needs 0 < red < cycle (strict, like the DB check)", () => {
    expect(validateCycleRequests([req({ redDurationSec: 150 })])).toEqual([
      "1행: 적색(150초)은 주기(150초)보다 짧아야 합니다.",
    ]);
    expect(validateCycleRequests([req({ redDurationSec: 0 })])).toEqual([
      "1행: 적색은 0보다 큰 정수(초)여야 합니다.",
    ]);
    expect(validateCycleRequests([req({ redDurationSec: Number.NaN })])).toHaveLength(1);
  });

  it("rejects empty, malformed, reversed and midnight-crossing bands", () => {
    expect(validateCycleRequests([req({ timeBandStart: "" })])).toEqual([
      "1행: 시간대는 HH:mm 형식이어야 합니다.",
    ]);
    expect(validateCycleRequests([req({ timeBandEnd: "24:00" })])).toHaveLength(1);
    expect(validateCycleRequests([req({ timeBandStart: "09:00", timeBandEnd: "09:00" })])).toEqual([
      "1행: 시작(09:00)이 끝(09:00)보다 앞이어야 합니다. 자정을 넘는 시간대는 두 행으로 나눕니다.",
    ]);
    expect(
      validateCycleRequests([req({ timeBandStart: "22:00", timeBandEnd: "02:00" })]),
    ).toHaveLength(1);
  });

  it("finds overlaps within a day type regardless of order", () => {
    expect(
      validateCycleRequests([
        req({ timeBandStart: "08:30", timeBandEnd: "11:00" }),
        req({ dayType: "SATURDAY" }),
        req({ timeBandStart: "07:00", timeBandEnd: "09:00" }),
      ]),
    ).toEqual(["3행과 1행의 평일 시간대가 겹칩니다. 끝과 시작이 맞닿는 것은 됩니다."]);
  });

  it("collects every problem at once", () => {
    expect(
      validateCycleRequests([
        req({ cycleDurationSec: 500, redDurationSec: -1 }),
        req({ timeBandStart: "11:00", timeBandEnd: "10:00" }),
      ]),
    ).toHaveLength(3);
  });
});

describe("drafts and requests", () => {
  it("only user observed rows become drafts; seconds are dropped when zero", () => {
    const drafts = draftsFromCycles([
      row({ source: "PUBLIC_API" }),
      row({
        id: 50,
        source: "USER_OBSERVED",
        timeBandEnd: "23:59:59",
        cycleDurationSec: 90,
        redDurationSec: 40,
      }),
    ]);
    expect(drafts).toEqual([
      {
        key: "cycle-row-50",
        dayType: "WEEKDAY",
        start: "07:00",
        end: "23:59:59",
        cycleSec: "90",
        redSec: "40",
      },
    ]);
    expect(toCycleRequests(drafts)).toEqual([
      {
        dayType: "WEEKDAY",
        timeBandStart: "07:00",
        timeBandEnd: "23:59:59",
        cycleDurationSec: 90,
        redDurationSec: 40,
      },
    ]);
  });

  it("non-integer input stays NaN so validation catches it", () => {
    const [r] = toCycleRequests([{ ...newCycleDraft(), cycleSec: "12.5", redSec: "" }]);
    expect(r!.cycleDurationSec).toBeNaN();
    expect(r!.redDurationSec).toBeNaN();
  });

  it("parses times", () => {
    expect(secondsOfDay("07:30")).toBe(27000);
    expect(secondsOfDay("23:59:59")).toBe(86399);
    expect(secondsOfDay("07:30:15.5")).toBe(27015);
    expect(secondsOfDay("7:30")).toBeNull();
    expect(secondsOfDay("25:00")).toBeNull();
    expect(timeInputValue("07:00:00")).toBe("07:00");
    expect(timeInputValue("07:00:30")).toBe("07:00:30");
  });
});

describe("preview", () => {
  it("is R²/(2C) and R/C", () => {
    expect(waitPreview(120, 90)).toEqual({ meanWaitSec: 33.75, redShare: 0.75 });
    expect(waitPreview(150, 110)!.meanWaitSec).toBeCloseTo(40.333, 3);
    expect(waitPreview(120, 120)).toBeNull();
    expect(waitPreview(0, 0)).toBeNull();
  });
});

describe("effectiveCycle (analytics resolve_cycle)", () => {
  const rows = [
    row({
      source: "DEFAULT_ASSUMPTION",
      timeBandStart: "00:00:00",
      timeBandEnd: "23:59:59",
      cycleDurationSec: 130,
      redDurationSec: 95,
    }),
    row({ source: "PUBLIC_API" }),
    row({
      source: "USER_OBSERVED",
      timeBandStart: "08:00:00",
      timeBandEnd: "09:00:00",
      cycleDurationSec: 100,
      redDurationSec: 40,
    }),
  ];

  it("prefers user observed > public api > default assumption among bands containing the time", () => {
    expect(effectiveCycle(rows, "WEEKDAY", "08:30").source).toBe("USER_OBSERVED");
    expect(effectiveCycle(rows, "WEEKDAY", "09:00").source).toBe("PUBLIC_API");
    expect(effectiveCycle(rows, "WEEKDAY", "12:00").source).toBe("DEFAULT_ASSUMPTION");
  });

  it("falls back to the analytics default", () => {
    expect(effectiveCycle(rows, "SATURDAY", "08:30")).toEqual({
      cycleSec: 120,
      redSec: 90,
      source: null,
    });
  });

  it("sorts like the server", () => {
    const sorted = sortCycles([row({ dayType: "SATURDAY", timeBandStart: "06:00:00" }), ...rows]);
    expect(sorted.map((r) => `${r.dayType} ${r.timeBandStart} ${r.source}`)).toEqual([
      "WEEKDAY 00:00:00 DEFAULT_ASSUMPTION",
      "WEEKDAY 07:00:00 PUBLIC_API",
      "WEEKDAY 08:00:00 USER_OBSERVED",
      "SATURDAY 06:00:00 PUBLIC_API",
    ]);
  });
});
