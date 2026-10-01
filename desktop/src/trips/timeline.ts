import type { BoardingAttempt, CommuteTrip, RouteLeg } from "../api/client";

/** 실제 출발 − 예측(스냅샷) 초. 둘 중 하나라도 없으면 null. 양수면 예측보다 늦게 떠났다. */
export function predictionErrorSec(attempt: BoardingAttempt): number | null {
  const { vehicleActualDepartureAt: actual, vehicleScheduledOrPredictedAt: predicted } = attempt;
  if (!actual || !predicted) return null;
  return Math.round((Date.parse(actual) - Date.parse(predicted)) / 1000);
}

export type TimelineEvent =
  | { kind: "LEFT_HOME"; at: string | null }
  | { kind: "ARRIVED_STOP"; at: string | null; leg: LegRef }
  | {
      kind: "MISSED" | "DEPARTED" | "UNKNOWN";
      at: string | null;
      leg: LegRef;
      attempt: BoardingAttempt;
    }
  | { kind: "ALIGHTED"; at: string | null; leg: LegRef; attempt: BoardingAttempt }
  | { kind: "NO_ATTEMPT"; at: null; leg: LegRef }
  | { kind: "ARRIVED_DESTINATION"; at: string | null };

/** 타임라인 행이 가리키는 구간. 경로 상세를 못 받았으면 `leg`가 없고 id만 있다. */
export interface LegRef {
  id: number;
  leg?: RouteLeg;
}

const bySeq = (a: BoardingAttempt, b: BoardingAttempt) => a.attemptSeq - b.attemptSeq;

/**
 * 집 나섬 → (TRANSIT 구간마다) 정류장 도착 → 놓친 차들 → 탄 차 출발 → 하차 → 도착.
 * 순서는 기록 구조(구간 seqOrder → attemptSeq)로 정한다. 시각으로 정렬하지 않는 것은 빈 시각이 있어도
 * 자리를 지키고, 순서가 어긋난 기록(GPS 지연)이 눈에 띄게 하기 위해서다.
 * 정류장 도착은 첫 시도의 `arrivedAtStopAt`만 쓴다 (docs/API.md #42).
 */
export function buildTimeline(trip: CommuteTrip, legs?: RouteLeg[]): TimelineEvent[] {
  const byLeg = new Map<number, BoardingAttempt[]>();
  for (const a of trip.boardingAttempts) {
    byLeg.set(a.routeLegId, [...(byLeg.get(a.routeLegId) ?? []), a]);
  }
  // 경로 상세가 있으면 TRANSIT 구간 전부(기록 없는 구간 포함)를 seqOrder로, 없으면 응답 순서를 쓴다.
  const legRefs: LegRef[] = legs
    ? [...legs]
        .filter((l) => l.legType === "TRANSIT")
        .sort((a, b) => a.seqOrder - b.seqOrder)
        .map((l) => ({ id: l.id, leg: l }))
    : [...byLeg.keys()].map((id) => ({ id }));
  // 경로가 바뀌어 지금 구간 목록에 없는 기록도 버리지 않는다.
  for (const id of byLeg.keys()) {
    if (!legRefs.some((r) => r.id === id)) legRefs.push({ id });
  }

  const events: TimelineEvent[] = [{ kind: "LEFT_HOME", at: trip.leftHomeAt ?? null }];
  for (const leg of legRefs) {
    const attempts = [...(byLeg.get(leg.id) ?? [])].sort(bySeq);
    if (attempts.length === 0) {
      events.push({ kind: "NO_ATTEMPT", at: null, leg });
      continue;
    }
    events.push({ kind: "ARRIVED_STOP", at: attempts[0]!.arrivedAtStopAt ?? null, leg });
    for (const attempt of attempts) {
      const at = attempt.vehicleActualDepartureAt ?? null;
      if (attempt.result === "MISSED") {
        events.push({ kind: "MISSED", at, leg, attempt });
      } else if (attempt.result === "CAUGHT") {
        events.push({ kind: "DEPARTED", at, leg, attempt });
        events.push({ kind: "ALIGHTED", at: attempt.alightedAt ?? null, leg, attempt });
      } else {
        events.push({ kind: "UNKNOWN", at, leg, attempt });
      }
    }
  }
  events.push({ kind: "ARRIVED_DESTINATION", at: trip.arrivedDestinationAt ?? null });
  return events;
}

export interface TripSummary {
  /** 탄 차가 있는 TRANSIT 구간 수 / 전체(경로 상세가 없으면 기록이 있는 구간 수) */
  caughtLegs: number;
  transitLegs: number;
  allCaught: boolean;
  missed: number;
  /** 집 나섬 → 도착 초. 둘 중 하나라도 없으면 null */
  totalSec: number | null;
}

export function summarizeTrip(trip: CommuteTrip, legs?: RouteLeg[]): TripSummary {
  const legIds = new Set(trip.boardingAttempts.map((a) => a.routeLegId));
  for (const l of legs ?? []) if (l.legType === "TRANSIT") legIds.add(l.id);
  const caught = new Set(
    trip.boardingAttempts.filter((a) => a.result === "CAUGHT").map((a) => a.routeLegId),
  );
  const { leftHomeAt, arrivedDestinationAt } = trip;
  return {
    caughtLegs: caught.size,
    transitLegs: legIds.size,
    allCaught: legIds.size > 0 && caught.size === legIds.size,
    missed: trip.boardingAttempts.filter((a) => a.result === "MISSED").length,
    totalSec:
      leftHomeAt && arrivedDestinationAt
        ? Math.round((Date.parse(arrivedDestinationAt) - Date.parse(leftHomeAt)) / 1000)
        : null,
  };
}
