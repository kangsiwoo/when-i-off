/**
 * 구간 편집의 초안 ↔ 요청 변환과 클라이언트 검증 (#64, 화면 없는 순수 로직).
 *
 * 검증은 서버 규칙을 **그대로** 옮긴 것이다. 서버를 바꾸면 여기도 같이 바꾼다.
 * - backend/src/main/kotlin/com/kangsiwoo/whenioff/route/application/RouteLegService.kt
 *   `validateSequence` / `requireContiguous` / `matchExisting`(id 중복) / `applyWalk` / `applyTransit`
 * - backend/src/main/kotlin/com/kangsiwoo/whenioff/route/api/RouteDtos.kt
 *   `RouteLegRequest`의 `@Positive`·`@DecimalMin/Max`, `ReplaceRouteLegsRequest.legs`의 `@NotEmpty`
 * - DB `route_legs.chk_leg_fields` (V1__init_schema.sql)
 *
 * 서버만 알 수 있는 것(실측 기록이 붙은 구간의 삭제·종류 변경 → 409, 이 경로의 구간이 아닌 id,
 * 없는 노선·정류장 id)은 여기서 검사하지 않는다. 그 응답의 `detail`은 화면이 그대로 보여 준다.
 */
import type { RouteLeg, RouteLegRequest, TransitLine, TransitStop } from "../api/client";
import { isValidLatLng, walkDistanceM, type LatLng } from "../map/geo";

export interface WalkDraft {
  /** 화면 key. 저장된 구간은 `leg-<id>`, 새 구간은 임의 값. */
  key: string;
  id?: number;
  legType: "WALK";
  start: LatLng | null;
  end: LatLng | null;
  plannedDistanceM: number | null;
  /** 사용자가 거리를 직접 고쳤으면 끝점을 옮겨도 직선거리로 덮지 않는다. */
  distanceEdited: boolean;
}

export interface TransitDraft {
  key: string;
  id?: number;
  legType: "TRANSIT";
  line: TransitLine | null;
  boardStop: TransitStop | null;
  alightStop: TransitStop | null;
  plannedTravelSec: number | null;
}

export type LegDraft = WalkDraft | TransitDraft;

let nextKey = 0;
export const newKey = () => `new-${++nextKey}`;

export function newWalk(start: LatLng | null, end: LatLng | null): WalkDraft {
  return {
    key: newKey(),
    legType: "WALK",
    start,
    end,
    plannedDistanceM: start && end ? walkDistanceM(start, end) : null,
    distanceEdited: false,
  };
}

export function newTransit(): TransitDraft {
  return {
    key: newKey(),
    legType: "TRANSIT",
    line: null,
    boardStop: null,
    alightStop: null,
    plannedTravelSec: null,
  };
}

/** 서버 응답(경로 상세의 구간) → 초안. seqOrder 순으로 정렬한다. */
export function draftsFromLegs(legs: RouteLeg[]): LegDraft[] {
  return [...legs]
    .sort((a, b) => a.seqOrder - b.seqOrder)
    .map((leg): LegDraft => {
      if (leg.legType === "WALK") {
        const start =
          leg.startLat != null && leg.startLng != null
            ? { lat: leg.startLat, lng: leg.startLng }
            : null;
        const end =
          leg.endLat != null && leg.endLng != null ? { lat: leg.endLat, lng: leg.endLng } : null;
        return {
          key: `leg-${leg.id}`,
          id: leg.id,
          legType: "WALK",
          start,
          end,
          plannedDistanceM: leg.plannedDistanceM ?? null,
          // 저장된 값은 사용자가 정한 값으로 본다 — 끝점을 끌어도 덮지 않는다.
          distanceEdited: leg.plannedDistanceM != null,
        };
      }
      return {
        key: `leg-${leg.id}`,
        id: leg.id,
        legType: "TRANSIT",
        line: leg.transitLine ?? null,
        boardStop: leg.boardStop ?? null,
        alightStop: leg.alightStop ?? null,
        plannedTravelSec: leg.plannedTravelSec ?? null,
      };
    });
}

/**
 * 초안 → `PUT /commute-routes/{id}/legs` 본문. seqOrder는 화면 순서대로 1부터 매긴다. 저장된 구간은 `id`를
 * 붙여 보낸다 — 재정렬해도 같은 구간(실측 기록·crossing)이 유지된다 (API.md "구간 교체의 의미").
 * 종류에 속하지 않는 필드는 보내지 않는다(서버가 "must be empty"로 거절한다).
 */
export function toLegRequests(drafts: LegDraft[]): RouteLegRequest[] {
  return drafts.map((d, i) => {
    const base = { ...(d.id != null ? { id: d.id } : {}), seqOrder: i + 1, legType: d.legType };
    if (d.legType === "WALK") {
      return {
        ...base,
        startLat: d.start?.lat ?? null,
        startLng: d.start?.lng ?? null,
        endLat: d.end?.lat ?? null,
        endLng: d.end?.lng ?? null,
        plannedDistanceM: d.plannedDistanceM,
      };
    }
    return {
      ...base,
      transitLineId: d.line?.id ?? null,
      boardStopId: d.boardStop?.id ?? null,
      alightStopId: d.alightStop?.id ?? null,
      plannedTravelSec: d.plannedTravelSec,
    };
  });
}

const present = (v: number | null | undefined): v is number => v != null;
const isPositive = (v: number) => Number.isFinite(v) && v > 0;

/**
 * 요청 목록의 문제를 서버가 검사하는 순서대로 모은다. 빈 배열이면 서버 규칙상 400이 날 이유가 없다.
 * (서버는 첫 문제에서 멈추지만 화면은 한 번에 다 보여 준다.)
 */
export function validateLegRequests(requests: RouteLegRequest[]): string[] {
  // ReplaceRouteLegsRequest: @NotEmpty legs
  if (requests.length === 0) return ["구간이 하나 이상 있어야 합니다."];
  const errors: string[] = [];
  const ordered = [...requests].sort((a, b) => a.seqOrder - b.seqOrder);

  // RouteLegRequest: @Positive seqOrder, plannedDistanceM, plannedTravelSec; 좌표 @DecimalMin/Max
  for (const r of ordered) {
    const p = `구간 ${r.seqOrder}`;
    if (!(Number.isInteger(r.seqOrder) && r.seqOrder > 0))
      errors.push(`${p}: 순서는 양수여야 합니다.`);
    for (const [label, lat, lng] of [
      ["시작", r.startLat, r.startLng],
      ["끝", r.endLat, r.endLng],
    ] as const) {
      if (
        (present(lat) && !isValidLatLng({ lat, lng: 0 })) ||
        (present(lng) && !isValidLatLng({ lat: 0, lng }))
      ) {
        errors.push(`${p}: ${label} 좌표가 WGS84 범위(위도 ±90, 경도 ±180)를 벗어났습니다.`);
      }
    }
    if (present(r.plannedDistanceM) && !isPositive(r.plannedDistanceM)) {
      errors.push(`${p}: 계획 거리는 0보다 커야 합니다.`);
    }
    if (
      present(r.plannedTravelSec) &&
      !(Number.isInteger(r.plannedTravelSec) && r.plannedTravelSec > 0)
    ) {
      errors.push(`${p}: 계획 소요는 0보다 큰 정수(초)여야 합니다.`);
    }
  }

  // validateSequence → requireContiguous(legs)
  const seqs = ordered.map((r) => r.seqOrder);
  if (seqs.some((s, i) => s !== i + 1)) {
    errors.push(`구간 순서는 1부터 빠짐없이 이어져야 합니다 (받은 순서 ${seqs.join(", ")}).`);
  }
  // validateSequence: 처음과 끝은 WALK
  if (ordered[0]!.legType !== "WALK" || ordered[ordered.length - 1]!.legType !== "WALK") {
    errors.push("경로는 도보 구간으로 시작하고 도보 구간으로 끝나야 합니다.");
  }
  // validateSequence: WALK/TRANSIT 교대
  for (let i = 1; i < ordered.length; i++) {
    const a = ordered[i - 1]!;
    const b = ordered[i]!;
    if (a.legType === b.legType) {
      errors.push(
        `도보와 대중교통이 번갈아 와야 합니다 (구간 ${a.seqOrder}·${b.seqOrder}이 둘 다 ${
          a.legType === "WALK" ? "도보" : "대중교통"
        }).`,
      );
    }
  }
  // matchExisting: 같은 id가 두 번
  const ids = ordered.map((r) => r.id).filter(present);
  for (const id of new Set(ids)) {
    if (ids.filter((x) => x === id).length > 1) errors.push(`구간 id ${id}가 두 번 들어 있습니다.`);
  }

  // applyWalk / applyTransit (+ DB chk_leg_fields)
  for (const r of ordered) {
    if (r.legType === "WALK") {
      const p = `구간 ${r.seqOrder} (도보)`;
      if (![r.startLat, r.startLng, r.endLat, r.endLng].every(present)) {
        errors.push(`${p}: 시작·끝 좌표가 필요합니다.`);
      }
      if ([r.transitLineId, r.boardStopId, r.alightStopId, r.plannedTravelSec].some(present)) {
        errors.push(`${p}: 대중교통 필드는 비워야 합니다.`);
      }
    } else {
      const p = `구간 ${r.seqOrder} (대중교통)`;
      if (!present(r.transitLineId)) errors.push(`${p}: 노선을 고르세요.`);
      if (!present(r.boardStopId)) errors.push(`${p}: 승차 정류장을 고르세요.`);
      if (!present(r.alightStopId)) errors.push(`${p}: 하차 정류장을 고르세요.`);
      if (!present(r.plannedTravelSec)) errors.push(`${p}: 계획 소요를 넣으세요.`);
      if ([r.startLat, r.startLng, r.endLat, r.endLng, r.plannedDistanceM].some(present)) {
        errors.push(`${p}: 도보 필드는 비워야 합니다.`);
      }
      if (present(r.boardStopId) && r.boardStopId === r.alightStopId) {
        errors.push(`${p}: 승차·하차 정류장이 달라야 합니다.`);
      }
    }
  }
  return errors;
}

/**
 * 초안 전체 검증: 요청 규칙 + 서버가 노선·정류장을 읽은 뒤 거는 규칙(정류장 mode = 노선 mode,
 * `applyTransit`). 초안에는 노선·정류장 객체가 있으므로 저장 전에 알 수 있다.
 */
export function validateDrafts(drafts: LegDraft[]): string[] {
  const errors = validateLegRequests(toLegRequests(drafts));
  drafts.forEach((d, i) => {
    if (d.legType !== "TRANSIT" || !d.line) return;
    const mode = d.line.mode;
    if (
      (d.boardStop && d.boardStop.mode !== mode) ||
      (d.alightStop && d.alightStop.mode !== mode)
    ) {
      errors.push(`구간 ${i + 1} (대중교통): 정류장 종류가 노선(${mode})과 같아야 합니다.`);
    }
  });
  return errors;
}

/** 위아래로 옮긴 새 배열. 범위를 벗어나면 그대로. */
export function moveDraft<T>(list: T[], index: number, delta: -1 | 1): T[] {
  const to = index + delta;
  if (index < 0 || index >= list.length || to < 0 || to >= list.length) return list;
  const next = [...list];
  [next[index], next[to]] = [next[to]!, next[index]!];
  return next;
}
