import type {
  AttemptResult,
  BoardingAttempt,
  CommuteTrip,
  UpdateBoardingAttemptRequest,
  UpdateCommuteTripRequest,
} from "../api/client";
import { isoToKstInput, kstInputToIso } from "../kst";

// 보정 폼은 KST datetime-local 문자열로 다루고, 보낼 때 UTC ISO로 바꾼다.
// PATCH는 null(생략) 필드를 건드리지 않고 값을 지우는 API도 없다 (docs/API.md). 그래서 바뀐 필드만 보내고,
// 원래 값이 있던 시각 칸을 비우면 "지울 수 없다"는 오류로 막는다 — 조용히 무시하면 지운 줄 안다.

export type PatchResult<T> = { ok: true; patch: T } | { ok: false; message: string };

export interface TripTimesForm {
  leftHomeAt: string;
  arrivedDestinationAt: string;
}

export interface AttemptForm {
  vehicleActualDepartureAt: string;
  alightedAt: string;
  result: AttemptResult;
  notes: string;
}

export const tripTimesForm = (trip: CommuteTrip): TripTimesForm => ({
  leftHomeAt: isoToKstInput(trip.leftHomeAt),
  arrivedDestinationAt: isoToKstInput(trip.arrivedDestinationAt),
});

export const attemptForm = (a: BoardingAttempt): AttemptForm => ({
  vehicleActualDepartureAt: isoToKstInput(a.vehicleActualDepartureAt),
  alightedAt: isoToKstInput(a.alightedAt),
  result: a.result,
  notes: a.notes ?? "",
});

/** 시각 칸 하나: 바뀌지 않았으면 undefined, 바뀌었으면 UTC ISO, 잘못됐으면 오류 메시지. */
function timeChange(
  label: string,
  original: string | null | undefined,
  input: string,
): { value?: string; error?: string } {
  const before = isoToKstInput(original);
  if (input.trim() === "") {
    return before === "" ? {} : { error: `${label}: 기록된 시각은 지울 수 없습니다.` };
  }
  const iso = kstInputToIso(input);
  if (!iso) return { error: `${label}: 시각 형식이 올바르지 않습니다.` };
  // 브라우저는 0초를 `HH:mm`으로 줄여 돌려준다. 문자열이 아니라 초 단위 시각으로 비교한다.
  if (before !== "" && iso === kstInputToIso(before)) return {};
  return { value: iso };
}

function collect<T extends object>(
  fields: [keyof T, string, string | null | undefined, string][],
): PatchResult<T> {
  const patch: Partial<Record<keyof T, string>> = {};
  for (const [key, label, original, input] of fields) {
    const { value, error } = timeChange(label, original, input);
    if (error) return { ok: false, message: error };
    if (value) patch[key] = value;
  }
  return { ok: true, patch: patch as T };
}

export function tripPatch(
  trip: CommuteTrip,
  form: TripTimesForm,
): PatchResult<UpdateCommuteTripRequest> {
  return collect<UpdateCommuteTripRequest>([
    ["leftHomeAt", "집 나섬", trip.leftHomeAt, form.leftHomeAt],
    ["arrivedDestinationAt", "도착", trip.arrivedDestinationAt, form.arrivedDestinationAt],
  ]);
}

export function attemptPatch(
  attempt: BoardingAttempt,
  form: AttemptForm,
): PatchResult<UpdateBoardingAttemptRequest> {
  const times = collect<UpdateBoardingAttemptRequest>([
    [
      "vehicleActualDepartureAt",
      "출발",
      attempt.vehicleActualDepartureAt,
      form.vehicleActualDepartureAt,
    ],
    ["alightedAt", "하차", attempt.alightedAt, form.alightedAt],
  ]);
  if (!times.ok) return times;
  const patch = { ...times.patch };
  if (form.result !== attempt.result) patch.result = form.result;
  // 메모는 빈 문자열을 보내면 비워진다 (null만 "그대로"다).
  if (form.notes !== (attempt.notes ?? "")) patch.notes = form.notes;
  return { ok: true, patch };
}

export const isEmptyPatch = (patch: object) => Object.keys(patch).length === 0;
