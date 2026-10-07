import type { TransitLine } from "../api/client";

/**
 * 지도 위 대중교통 선의 색(#96). 소개 페이지와 같은 실제 노선색 토큰(`--gtx-a` 등)을 이름으로 고른다.
 * 노선별 공식 색을 다 들고 있지 않으므로 아는 노선만 그 색이고, 모르는 지하철은 잉크색이다.
 * 광역·시내버스를 구분할 정보가 없어 버스는 모두 광역버스 빨강이다.
 */
export type LineTone = "gtx-a" | "bus-red" | "line-2" | "suin" | "ink";

export function lineTone(line: Pick<TransitLine, "name" | "mode"> | null | undefined): LineTone {
  if (!line) return "ink";
  const name = line.name.replace(/\s+/g, "");
  if (line.mode === "GTX" || /GTX/i.test(name)) return "gtx-a";
  if (line.mode === "BUS") return "bus-red";
  if (/수인|분당/.test(name)) return "suin";
  if (/^2호선|^(서울)?지하철2호선/.test(name)) return "line-2";
  return "ink";
}
