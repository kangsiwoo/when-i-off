import type { CommuteRoute, RouteLeg } from "./api/client";

const DIRECTION: Record<CommuteRoute["direction"], string> = {
  TO_WORK: "출근",
  TO_HOME: "퇴근",
};

const LEG_TYPE: Record<RouteLeg["legType"], string> = {
  WALK: "도보",
  TRANSIT: "대중교통",
};

type TransitMode = NonNullable<RouteLeg["transitLine"]>["mode"];

const MODE: Record<TransitMode, string> = {
  BUS: "버스",
  SUBWAY: "지하철",
  GTX: "GTX",
};

export const directionLabel = (d: CommuteRoute["direction"]) => DIRECTION[d];
export const legTypeLabel = (t: RouteLeg["legType"]) => LEG_TYPE[t];
export const modeLabel = (m: TransitMode) => MODE[m];

// 저장·전송은 UTC, 표시할 때만 KST (docs/CONVENTIONS.md 공통 규칙).
const KST = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  dateStyle: "medium",
  timeStyle: "short",
});

export const formatKst = (iso: string) => KST.format(new Date(iso));

export function formatDistance(m: number | null | undefined): string {
  if (m == null) return "—";
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

export function formatDuration(sec: number | null | undefined): string {
  if (sec == null) return "—";
  const min = Math.floor(sec / 60);
  const rest = sec % 60;
  if (min === 0) return `${rest}초`;
  return rest === 0 ? `${min}분` : `${min}분 ${rest}초`;
}
