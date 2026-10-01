import type { CommuteRoute, DayType, UpdateCommuteRouteRequest } from "../api/client";

/** enum 선언 순서. 화면의 체크박스 순서이자 보낼 때의 정렬 순서다. */
export const DAY_TYPES: readonly DayType[] = ["WEEKDAY", "SATURDAY", "SUNDAY_HOLIDAY"];

/**
 * 경로의 기본 목표 도착 시각·대상 day_type 초안 (#68). `time`은 `<input type="time">` 값(`HH:mm`, 비우면 "").
 * 서버는 KST 벽시계 `HH:mm:ss`로 저장·응답한다.
 */
export interface TargetDraft {
  time: string;
  dayTypes: DayType[];
}

export const DEFAULT_TARGET: TargetDraft = { time: "", dayTypes: ["WEEKDAY"] };

/** 서버의 `HH:mm:ss`(또는 없음) → 입력 칸의 `HH:mm`. 초는 화면에서 다루지 않는다. */
export function toTimeInput(serverTime: string | null | undefined): string {
  return serverTime ? serverTime.slice(0, 5) : "";
}

export function targetFromRoute(route: CommuteRoute): TargetDraft {
  return {
    time: toTimeInput(route.defaultTargetArrivalTime),
    dayTypes: sortDayTypes(route.defaultTargetDayTypes),
  };
}

export function sortDayTypes(days: readonly DayType[]): DayType[] {
  return DAY_TYPES.filter((d) => days.includes(d));
}

export function toggleDayType(days: readonly DayType[], day: DayType): DayType[] {
  return sortDayTypes(days.includes(day) ? days.filter((d) => d !== day) : [...days, day]);
}

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

/** 보내기 전 검사. 서버 규칙(빈 day_type 집합 400, 시각 형식)과 같다. 문제가 없으면 null. */
export function validateTarget(draft: TargetDraft): string | null {
  if (draft.time !== "" && !TIME.test(draft.time))
    return "목표 도착 시각은 HH:mm 형식이어야 합니다.";
  if (draft.dayTypes.length === 0) return "추천할 날을 하나 이상 고르세요.";
  return null;
}

/** 생성 요청에 붙일 필드. 시각이 비면 보내지 않는다(서버 기본: 없음). */
export function targetCreateFields(draft: TargetDraft): {
  defaultTargetArrivalTime?: string;
  defaultTargetDayTypes: DayType[];
} {
  return {
    ...(draft.time !== "" ? { defaultTargetArrivalTime: draft.time } : {}),
    defaultTargetDayTypes: sortDayTypes(draft.dayTypes),
  };
}

export interface RouteSettingsDraft extends TargetDraft {
  name: string;
  isActive: boolean;
}

export function settingsFromRoute(route: CommuteRoute): RouteSettingsDraft {
  return { name: route.name, isActive: route.isActive, ...targetFromRoute(route) };
}

/**
 * PATCH 본문: 바뀐 필드만. 서버는 null을 "그대로"로 보므로 목표 시각을 지울 때는
 * `clearDefaultTargetArrivalTime`을 보낸다. 바뀐 게 없으면 null.
 */
export function settingsPatch(
  route: CommuteRoute,
  draft: RouteSettingsDraft,
): UpdateCommuteRouteRequest | null {
  const before = settingsFromRoute(route);
  const body: UpdateCommuteRouteRequest = {};
  const name = draft.name.trim();
  if (name !== before.name) body.name = name;
  if (draft.isActive !== before.isActive) body.isActive = draft.isActive;
  if (draft.time !== before.time) {
    if (draft.time === "") body.clearDefaultTargetArrivalTime = true;
    else body.defaultTargetArrivalTime = draft.time;
  }
  const days = sortDayTypes(draft.dayTypes);
  if (days.join() !== before.dayTypes.join()) body.defaultTargetDayTypes = days;
  return Object.keys(body).length > 0 ? body : null;
}
