/**
 * "다음 출발" 확인 (#70, 화면 없는 순수 로직): 입력 초안 → `GET /transit-lines/{id}/schedules/next` 파라미터,
 * 그리고 노선·정류장·방향 후보.
 *
 * 검사는 서버(`TransitScheduleService.nextDepartures`)와 같은 규칙이다: `limit`은 1~50, `direction`은 trim 뒤
 * 비어 있으면 안 된다. 노선·정류장이 없으면(404) 서버 `detail`을 그대로 보여 준다.
 */
import type { CommuteRouteDetail, TransitLine, TransitStop } from "../api/client";
import type { NextDeparturesQuery } from "../api/queries";
import { isoToKstInput, kstInputToIso } from "../kst";
import type { ScheduleRow } from "./csv";

export const DEFAULT_LIMIT = 5;
export const MAX_LIMIT = 50;

export interface NextDraft {
  lineId: number | null;
  /** 검색으로 골랐거나 내 경로에서 아는 노선. id만 아는 노선(CSV에서 채움)이면 null. */
  line: TransitLine | null;
  stopId: string;
  direction: string;
  /** datetime-local 값(KST 벽시계). */
  at: string;
  limit: string;
}

/** 지금의 KST 벽시계 (datetime-local 값). */
export const nowKstInput = (now: Date = new Date()) => isoToKstInput(now.toISOString());

export function emptyDraft(now: Date = new Date()): NextDraft {
  return {
    lineId: null,
    line: null,
    stopId: "",
    direction: "",
    at: nowKstInput(now),
    limit: String(DEFAULT_LIMIT),
  };
}

/** 방금 올린 파일의 첫 조합으로 채운다. 기준 시각·대수는 그대로 둔다. */
export function draftFromRows(
  draft: NextDraft,
  rows: ScheduleRow[],
  known: KnownTransit,
): NextDraft {
  const first = rows[0];
  if (!first) return draft;
  const lineId = Number(first.lineId);
  if (!Number.isSafeInteger(lineId)) return draft;
  return {
    ...draft,
    lineId,
    line: known.lines.get(lineId) ?? null,
    stopId: first.stopId,
    direction: first.directionCode,
  };
}

const POSITIVE_INT = /^[1-9][0-9]*$/;

export function nextQuery(
  d: NextDraft,
): { query: NextDeparturesQuery; problem?: never } | { problem: string; query?: never } {
  if (d.lineId == null) return { problem: "노선을 고르세요." };
  const stop = d.stopId.trim();
  if (!POSITIVE_INT.test(stop) || !Number.isSafeInteger(Number(stop))) {
    return { problem: "정류장 id를 숫자로 넣으세요." };
  }
  const direction = d.direction.trim();
  if (direction === "") return { problem: "방향 코드를 넣으세요 (예: UP, DN)." };
  const at = kstInputToIso(d.at);
  if (!at) return { problem: "기준 시각을 넣으세요." };
  const limit = Number(d.limit);
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    return { problem: `대수는 1~${MAX_LIMIT} 사이여야 합니다.` };
  }
  return { query: { lineId: d.lineId, stopId: Number(stop), direction, at, limit } };
}

/** `minutesUntil` 표시: `9분 뒤`, `6시간 뒤`, `6시간 15분 뒤`. */
export function formatWait(minutes: number): string {
  if (minutes < 60) return `${minutes}분 뒤`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h}시간 뒤` : `${h}시간 ${m}분 뒤`;
}

/** 기준 시각에서 출발까지 분(내림). 같은 시각이면 0. */
export function minutesUntil(fromIso: string, toIso: string): number {
  return Math.floor((Date.parse(toIso) - Date.parse(fromIso)) / 60_000);
}

/** 내 경로의 TRANSIT 구간에서 아는 노선·정류장 (노선·정류장 단건 조회 API가 없어서 이름은 여기서만 안다). */
export interface KnownTransit {
  lines: Map<number, TransitLine>;
  stops: Map<number, TransitStop>;
  /** 노선 id → 그 노선의 구간에서 쓰는 정류장 id (처음 나온 순서). */
  stopsByLine: Map<number, number[]>;
}

export function knownTransit(details: (CommuteRouteDetail | undefined)[]): KnownTransit {
  const known: KnownTransit = { lines: new Map(), stops: new Map(), stopsByLine: new Map() };
  for (const d of details) {
    for (const leg of d?.legs ?? []) {
      if (!leg.transitLine) continue;
      known.lines.set(leg.transitLine.id, leg.transitLine);
      const list = known.stopsByLine.get(leg.transitLine.id) ?? [];
      for (const stop of [leg.boardStop, leg.alightStop]) {
        if (!stop) continue;
        known.stops.set(stop.id, stop);
        if (!list.includes(stop.id)) list.push(stop.id);
      }
      known.stopsByLine.set(leg.transitLine.id, list);
    }
  }
  return known;
}

/** 미리보기·표에 쓰는 노선 이름. 내 경로에서 아는 노선이면 이름, 아니면 id만. */
export function lineName(id: string | number, known: KnownTransit): string {
  const line = known.lines.get(Number(id));
  return line ? `${line.name} #${id}` : `노선 #${id}`;
}

export function stopName(id: string | number, known: KnownTransit): string {
  const stop = known.stops.get(Number(id));
  return stop ? `${stop.name} #${id}` : `정류장 #${id}`;
}

/** 정류장 후보: 방금 고른 CSV에서 이 노선에 나온 정류장 → 내 경로 구간의 정류장. */
export function stopCandidates(
  lineId: number | null,
  rows: ScheduleRow[],
  known: KnownTransit,
): number[] {
  if (lineId == null) return [];
  const ids: number[] = [];
  const add = (id: number) => {
    if (Number.isSafeInteger(id) && id > 0 && !ids.includes(id)) ids.push(id);
  };
  for (const r of rows) if (r.lineId === String(lineId)) add(Number(r.stopId));
  for (const id of known.stopsByLine.get(lineId) ?? []) add(id);
  return ids;
}

/** 방향 후보: CSV에서 이 노선·정류장에 나온 방향 코드. */
export function directionCandidates(
  lineId: number | null,
  stopId: string,
  rows: ScheduleRow[],
): string[] {
  const out: string[] = [];
  for (const r of rows) {
    if (
      r.lineId === String(lineId) &&
      r.stopId === stopId.trim() &&
      !out.includes(r.directionCode)
    ) {
      out.push(r.directionCode);
    }
  }
  return out;
}
