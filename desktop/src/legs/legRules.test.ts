import { describe, expect, it } from "vitest";
import type { RouteLegRequest, TransitLine, TransitStop } from "../api/client";
import { detail } from "../test/fixtures";
import {
  draftsFromLegs,
  moveDraft,
  newTransit,
  newWalk,
  toCrossingRequests,
  toLegRequests,
  validateCrossingRequests,
  validateDrafts,
  validateLegRequests,
  type LegDraft,
} from "./legRules";

const walk = (seqOrder: number, extra: Partial<RouteLegRequest> = {}): RouteLegRequest => ({
  seqOrder,
  legType: "WALK",
  startLat: 37.2,
  startLng: 127.07,
  endLat: 37.21,
  endLng: 127.08,
  plannedDistanceM: 650,
  ...extra,
});

const transit = (seqOrder: number, extra: Partial<RouteLegRequest> = {}): RouteLegRequest => ({
  seqOrder,
  legType: "TRANSIT",
  transitLineId: 3,
  boardStopId: 4,
  alightStopId: 5,
  plannedTravelSec: 1200,
  ...extra,
});

const line: TransitLine = {
  id: 3,
  mode: "GTX",
  name: "GTX-A",
  hasRealtimeApi: false,
  createdAt: "2026-09-10T00:00:00Z",
};
const stop = (id: number, mode: TransitStop["mode"] = "GTX"): TransitStop => ({
  id,
  mode,
  name: `역 ${id}`,
  lat: 37.2,
  lng: 127.09,
  createdAt: "2026-09-10T00:00:00Z",
});

describe("validateLegRequests (RouteLegService.validateSequence)", () => {
  it("accepts WALK → TRANSIT → WALK", () => {
    expect(validateLegRequests([walk(1), transit(2), walk(3)])).toEqual([]);
  });

  it("accepts a single WALK leg", () => {
    expect(validateLegRequests([walk(1)])).toEqual([]);
  });

  it("rejects an empty list (@NotEmpty)", () => {
    expect(validateLegRequests([])).toEqual(["구간이 하나 이상 있어야 합니다."]);
  });

  it("rejects gaps in seqOrder", () => {
    expect(validateLegRequests([walk(1), transit(2), walk(4)])).toContain(
      "구간 순서는 1부터 빠짐없이 이어져야 합니다 (받은 순서 1, 2, 4).",
    );
  });

  it("sorts by seqOrder before checking, like the server", () => {
    expect(validateLegRequests([walk(3), walk(1), transit(2)])).toEqual([]);
  });

  it("requires WALK at both ends", () => {
    const msg = "경로는 도보 구간으로 시작하고 도보 구간으로 끝나야 합니다.";
    expect(validateLegRequests([transit(1), walk(2)])).toContain(msg);
    expect(validateLegRequests([walk(1), transit(2)])).toContain(msg);
  });

  it("requires alternation", () => {
    expect(validateLegRequests([walk(1), walk(2)])).toContain(
      "도보와 대중교통이 번갈아 와야 합니다 (구간 1·2이 둘 다 도보).",
    );
  });

  it("rejects the same id twice (matchExisting)", () => {
    expect(validateLegRequests([walk(1, { id: 9 }), transit(2), walk(3, { id: 9 })])).toContain(
      "구간 id 9가 두 번 들어 있습니다.",
    );
  });
});

describe("validateLegRequests (applyWalk / applyTransit / DTO)", () => {
  it("WALK needs all four coordinates and no transit fields", () => {
    expect(validateLegRequests([walk(1, { endLng: null })])).toEqual([
      "구간 1 (도보): 시작·끝 좌표가 필요합니다.",
    ]);
    expect(validateLegRequests([walk(1, { plannedTravelSec: 480 })])).toEqual([
      "구간 1 (도보): 대중교통 필드는 비워야 합니다.",
    ]);
  });

  it("WALK distance is optional but must be positive (@Positive)", () => {
    expect(validateLegRequests([walk(1, { plannedDistanceM: null })])).toEqual([]);
    expect(validateLegRequests([walk(1, { plannedDistanceM: 0 })])).toEqual([
      "구간 1: 계획 거리는 0보다 커야 합니다.",
    ]);
  });

  it("checks WGS84 ranges (@DecimalMin/@DecimalMax)", () => {
    expect(validateLegRequests([walk(1, { startLat: 91 })])).toEqual([
      "구간 1: 시작 좌표가 WGS84 범위(위도 ±90, 경도 ±180)를 벗어났습니다.",
    ]);
    expect(validateLegRequests([walk(1, { endLng: -180.5 })])).toEqual([
      "구간 1: 끝 좌표가 WGS84 범위(위도 ±90, 경도 ±180)를 벗어났습니다.",
    ]);
    expect(validateLegRequests([walk(1, { startLat: 90, endLng: -180 })])).toEqual([]);
  });

  it("TRANSIT needs line, both stops and travel time, and no walk fields", () => {
    const errors = validateLegRequests([
      walk(1),
      transit(2, { transitLineId: null, boardStopId: null, plannedTravelSec: null, startLat: 1 }),
      walk(3),
    ]);
    expect(errors).toEqual([
      "구간 2 (대중교통): 노선을 고르세요.",
      "구간 2 (대중교통): 승차 정류장을 고르세요.",
      "구간 2 (대중교통): 계획 소요를 넣으세요.",
      "구간 2 (대중교통): 도보 필드는 비워야 합니다.",
    ]);
  });

  it("TRANSIT board and alight stop must differ; travel time is a positive integer", () => {
    expect(validateLegRequests([walk(1), transit(2, { alightStopId: 4 }), walk(3)])).toEqual([
      "구간 2 (대중교통): 승차·하차 정류장이 달라야 합니다.",
    ]);
    expect(validateLegRequests([walk(1), transit(2, { plannedTravelSec: 0 }), walk(3)])).toEqual([
      "구간 2: 계획 소요는 0보다 큰 정수(초)여야 합니다.",
    ]);
  });
});

describe("drafts", () => {
  it("round-trips the route detail into a PUT body with ids and renumbered seqOrder", () => {
    const drafts = draftsFromLegs(detail.legs);
    expect(drafts.map((d) => d.id)).toEqual([11, 12]);
    expect(toLegRequests(drafts)).toEqual([
      {
        id: 11,
        seqOrder: 1,
        legType: "WALK",
        startLat: 37.2,
        startLng: 127.07,
        endLat: 37.2,
        endLng: 127.09,
        plannedDistanceM: 650,
      },
      {
        id: 12,
        seqOrder: 2,
        legType: "TRANSIT",
        transitLineId: 3,
        boardStopId: 4,
        alightStopId: 5,
        plannedTravelSec: 1200,
      },
    ]);
  });

  it("does not send the WALK plannedTravelSec the detail may carry", () => {
    const req = toLegRequests(draftsFromLegs(detail.legs))[0]!;
    expect(req).not.toHaveProperty("plannedTravelSec");
  });

  it("new WALK pre-fills the straight-line distance", () => {
    const d = newWalk({ lat: 37.2, lng: 127.07 }, { lat: 37.2, lng: 127.09 });
    expect(d.plannedDistanceM).toBe(1771);
    expect(d.distanceEdited).toBe(false);
    expect(newWalk(null, null).plannedDistanceM).toBeNull();
  });

  it("validateDrafts adds the stop-mode rule from applyTransit", () => {
    const w = () => newWalk({ lat: 37.2, lng: 127.07 }, { lat: 37.21, lng: 127.08 });
    const t = {
      ...newTransit(),
      line,
      boardStop: stop(4),
      alightStop: stop(5, "BUS"),
      plannedTravelSec: 600,
    };
    const drafts: LegDraft[] = [w(), t, w()];
    expect(validateDrafts(drafts)).toEqual([
      "구간 2 (대중교통): 정류장 종류가 노선(GTX)과 같아야 합니다.",
    ]);
    expect(validateDrafts([w(), { ...t, alightStop: stop(5) }, w()])).toEqual([]);
  });

  it("moveDraft swaps neighbours and ignores out-of-range moves", () => {
    expect(moveDraft(["a", "b", "c"], 1, -1)).toEqual(["b", "a", "c"]);
    expect(moveDraft(["a", "b", "c"], 2, 1)).toEqual(["a", "b", "c"]);
    expect(moveDraft(["a", "b", "c"], 0, -1)).toEqual(["a", "b", "c"]);
  });
});

describe("signal crossings (RouteLegService.replaceSignalCrossings, SignalCodes)", () => {
  it("numbers crossings from 1 and accepts known codes", () => {
    const reqs = toCrossingRequests([
      { trafficSignalId: 9, approachDir: "nt", signalKind: "Pd" },
      { trafficSignalId: 10, approachDir: "sw", signalKind: "St" },
    ]);
    expect(reqs.map((r) => r.seqOrder)).toEqual([1, 2]);
    expect(validateCrossingRequests(reqs)).toEqual([]);
    expect(validateCrossingRequests([])).toEqual([]);
  });

  it("rejects unknown codes and gaps", () => {
    expect(
      validateCrossingRequests([
        { trafficSignalId: 9, seqOrder: 2, approachDir: "north", signalKind: "Pd" },
      ]),
    ).toEqual([
      "교차로 순서는 1부터 빠짐없이 이어져야 합니다 (받은 순서 2).",
      "교차로 2: 접근 방향은 nt, et, st, wt, ne, se, sw, nw 중 하나여야 합니다.",
    ]);
    expect(
      validateCrossingRequests([
        { trafficSignalId: 9, seqOrder: 1, approachDir: "nt", signalKind: "pd" },
      ]),
    ).toEqual(["교차로 1: 신호 종류는 Bs, Bc, Lt, Pd, St, Ut 중 하나여야 합니다."]);
  });
});
