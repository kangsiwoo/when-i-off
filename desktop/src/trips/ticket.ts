import type { BoardingAttempt, CommuteRoute, CommuteTrip, RouteLeg } from "../api/client";
import { formatDistance } from "../format";
import { lineTone, type LineTone } from "../map/lineTone";

/**
 * 승차권(#96): 출근·퇴근 한 번 = 승차권 한 장. 화면 없는 순수 로직이다.
 * 기록에 없는 값은 만들지 않는다 — 없으면 null이고 화면은 "—"로 그린다.
 */

export type TicketStamp = "ON_TIME" | "LATE" | "IN_PROGRESS" | "ARRIVED" | "CANCELLED";

export const STAMP_LABEL: Record<TicketStamp, string> = {
  ON_TIME: "정시",
  LATE: "지각",
  IN_PROGRESS: "기록 중",
  ARRIVED: "도착",
  CANCELLED: "취소",
};

export interface TicketMissed {
  attemptSeq: number;
  /** 놓친 차가 떠난 시각(UTC ISO). 기록이 없으면 null */
  at: string | null;
}

export type TicketSegment =
  | { kind: "walk"; key: string; seqOrder: number; distance: string | null }
  | {
      kind: "transit";
      key: string;
      seqOrder: number | null;
      tone: LineTone;
      line: string;
      board: string | null;
      alight: string | null;
      /** 탄 차의 출발·하차 시각. 탄 차가 없으면 null */
      departedAt: string | null;
      alightedAt: string | null;
      /** 탄 차가 있는가 / 결과 모름만 있는가 / 기록이 없는가 */
      status: "caught" | "unknown" | "none";
      missed: TicketMissed[];
    };

export interface Ticket {
  direction: string;
  from: string;
  to: string;
  leftHomeAt: string | null;
  arrivedAt: string | null;
  /** 경로의 기본 목표 도착 시각을 그날 KST에 붙인 UTC ISO. 경로에 없으면 null */
  targetAt: string | null;
  /** 도착 − 목표(초). 둘 중 하나라도 없으면 null. 양수면 늦게 도착 */
  arrivalDiffSec: number | null;
  stamp: TicketStamp;
  segments: TicketSegment[];
}

const KST_OFFSET = "+09:00";

/** 경로 기본 목표 시각(`HH:mm[:ss]`, KST)을 trip 날짜에 붙인다. */
export function ticketTargetAt(tripDate: string, time: string | null | undefined): string | null {
  if (!time || !/^\d{2}:\d{2}/.test(time)) return null;
  const hhmmss = time.length >= 8 ? time.slice(0, 8) : `${time.slice(0, 5)}:00`;
  const ms = Date.parse(`${tripDate}T${hhmmss}${KST_OFFSET}`);
  return Number.isNaN(ms) ? null : new Date(ms).toISOString().replace(".000Z", "Z");
}

/**
 * 도장: 취소(표시만, 서버는 취소한 trip을 지운다) → 도착 기록 없음 = 기록 중 → 목표가 없으면 도착 →
 * 1초라도 늦으면 지각(추천 vs 실제 표와 같은 기준), 아니면 정시.
 */
export function ticketStamp(
  arrivedAt: string | null,
  diffSec: number | null,
  cancelled = false,
): TicketStamp {
  if (cancelled) return "CANCELLED";
  if (!arrivedAt) return "IN_PROGRESS";
  if (diffSec === null) return "ARRIVED";
  return diffSec > 0 ? "LATE" : "ON_TIME";
}

const bySeq = (a: BoardingAttempt, b: BoardingAttempt) => a.attemptSeq - b.attemptSeq;

function transitSegment(
  key: string,
  leg: RouteLeg | undefined,
  attempts: BoardingAttempt[],
): TicketSegment {
  const sorted = [...attempts].sort(bySeq);
  const caught = sorted.find((a) => a.result === "CAUGHT");
  return {
    kind: "transit",
    key,
    seqOrder: leg?.seqOrder ?? null,
    tone: lineTone(leg?.transitLine),
    line: leg?.transitLine?.name ?? "노선 ?",
    board: leg?.boardStop?.name ?? null,
    alight: leg?.alightStop?.name ?? null,
    departedAt: caught?.vehicleActualDepartureAt ?? null,
    alightedAt: caught?.alightedAt ?? null,
    status: caught ? "caught" : sorted.length > 0 ? "unknown" : "none",
    missed: sorted
      .filter((a) => a.result === "MISSED")
      .map((a) => ({ attemptSeq: a.attemptSeq, at: a.vehicleActualDepartureAt ?? null })),
  };
}

/** 구간 띠: 경로 상세가 있으면 구간 순서대로(도보 포함), 없으면 기록이 있는 대중교통 구간만. */
export function ticketSegments(trip: CommuteTrip, legs?: RouteLeg[]): TicketSegment[] {
  const byLeg = new Map<number, BoardingAttempt[]>();
  for (const a of trip.boardingAttempts) {
    byLeg.set(a.routeLegId, [...(byLeg.get(a.routeLegId) ?? []), a]);
  }
  const segments: TicketSegment[] = [];
  for (const leg of [...(legs ?? [])].sort((a, b) => a.seqOrder - b.seqOrder)) {
    if (leg.legType === "WALK") {
      segments.push({
        kind: "walk",
        key: `leg-${leg.id}`,
        seqOrder: leg.seqOrder,
        distance: leg.plannedDistanceM == null ? null : formatDistance(leg.plannedDistanceM),
      });
    } else {
      segments.push(transitSegment(`leg-${leg.id}`, leg, byLeg.get(leg.id) ?? []));
    }
  }
  // 경로가 바뀌어 지금 구간 목록에 없는 기록도 버리지 않는다 (타임라인과 같은 규칙).
  for (const [id, attempts] of byLeg) {
    if (!legs?.some((l) => l.id === id))
      segments.push(transitSegment(`leg-${id}`, undefined, attempts));
  }
  return segments;
}

export function buildTicket(
  trip: CommuteTrip,
  route?: CommuteRoute,
  legs?: RouteLeg[],
  cancelled = false,
): Ticket {
  const toHome = route?.direction === "TO_HOME";
  const leftHomeAt = trip.leftHomeAt ?? null;
  const arrivedAt = trip.arrivedDestinationAt ?? null;
  const targetAt = ticketTargetAt(trip.tripDate, route?.defaultTargetArrivalTime);
  const arrivalDiffSec =
    arrivedAt && targetAt
      ? Math.round((Date.parse(arrivedAt) - Date.parse(targetAt)) / 1000)
      : null;
  return {
    direction: route ? (toHome ? "퇴근" : "출근") : "이동",
    from: route ? (toHome ? "회사" : "집") : "출발",
    to: route ? (toHome ? "집" : "회사") : "도착",
    leftHomeAt,
    arrivedAt,
    targetAt,
    arrivalDiffSec,
    stamp: ticketStamp(arrivedAt, arrivalDiffSec, cancelled),
    segments: ticketSegments(trip, legs),
  };
}

const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];

/** `2026-09-17` → `2026-09-17 (목)`. 달력 날짜라 시간대와 무관하다. */
export function ticketDate(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  return Number.isNaN(d.getTime()) ? date : `${date} (${WEEKDAY[d.getUTCDay()]})`;
}

/** 승차권 번호: trip id를 6자리로. */
export const ticketNo = (id: number) => `No. ${String(id).padStart(6, "0")}`;
