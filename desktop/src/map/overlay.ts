/** 지도에 올릴 마커·선을 API 응답에서 만든다 (화면 없는 순수 로직, #64). */
import type { BoardingAttempt, CommuteRoute, GpsTrace, RouteLeg } from "../api/client";
import { formatKstTime } from "../kst";
import { lineTone } from "./lineTone";
import type { MapLine, MapMarker } from "./types";

/** 경로 상세의 읽기 전용 지도: 출발/도착, 도보 선, 대중교통(승차→하차) 점선과 정류장. */
export function routeOverlay(route: CommuteRoute, legs: RouteLeg[]) {
  const markers: MapMarker[] = [
    {
      id: "origin",
      position: { lat: route.originLat, lng: route.originLng },
      kind: "origin",
      text: "집",
      tooltip: "출발",
    },
    {
      id: "destination",
      position: { lat: route.destinationLat, lng: route.destinationLng },
      kind: "destination",
      text: "끝",
      tooltip: "도착",
    },
  ];
  const lines: MapLine[] = [];
  for (const leg of [...legs].sort((a, b) => a.seqOrder - b.seqOrder)) {
    if (leg.legType === "WALK") {
      if (leg.startLat == null || leg.startLng == null || leg.endLat == null || leg.endLng == null)
        continue;
      lines.push({
        id: `leg-${leg.id}`,
        kind: "walk",
        positions: [
          { lat: leg.startLat, lng: leg.startLng },
          { lat: leg.endLat, lng: leg.endLng },
        ],
      });
    } else if (leg.boardStop && leg.alightStop) {
      const b = { lat: leg.boardStop.lat, lng: leg.boardStop.lng };
      const a = { lat: leg.alightStop.lat, lng: leg.alightStop.lng };
      lines.push({
        id: `leg-${leg.id}`,
        kind: "transit",
        tone: lineTone(leg.transitLine),
        positions: [b, a],
      });
      markers.push(
        stopMarker(leg, "board", `승차 ${leg.boardStop.name}`),
        stopMarker(leg, "alight", `하차 ${leg.alightStop.name}`),
      );
    }
  }
  return { markers, lines };
}

function stopMarker(leg: RouteLeg, which: "board" | "alight", tooltip: string): MapMarker {
  const stop = which === "board" ? leg.boardStop! : leg.alightStop!;
  return {
    id: `leg-${leg.id}-${which}`,
    position: { lat: stop.lat, lng: stop.lng },
    kind: "stop",
    text: String(leg.seqOrder),
    tooltip,
  };
}

/**
 * trip 상세의 GPS 오버레이: 기록 시각 순 폴리라인과 시작/끝 마커, 그리고 경로의 승하차 정류장에
 * 그 trip의 정류장 도착·하차 시각(KST)을 붙인다. 포인트는 서버가 시각순으로 주지만 한 번 더 정렬한다.
 */
export function gpsOverlay(traces: GpsTrace[], legs: RouteLeg[], attempts: BoardingAttempt[]) {
  const sorted = [...traces].sort((a, b) => a.recordedAt.localeCompare(b.recordedAt));
  const positions = sorted.map((t) => ({ lat: t.lat, lng: t.lng }));
  const markers: MapMarker[] = [];
  const lines: MapLine[] = [];
  if (positions.length > 1) lines.push({ id: "gps", kind: "gps", positions });
  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  if (first) {
    markers.push({
      id: "gps-start",
      position: positions[0]!,
      kind: "gps-start",
      text: "S",
      tooltip: `GPS 시작 ${formatKstTime(first.recordedAt)}`,
    });
  }
  if (last && sorted.length > 1) {
    markers.push({
      id: "gps-end",
      position: positions[positions.length - 1]!,
      kind: "gps-end",
      text: "E",
      tooltip: `GPS 끝 ${formatKstTime(last.recordedAt)}`,
    });
  }
  for (const leg of legs) {
    if (leg.legType !== "TRANSIT" || !leg.boardStop || !leg.alightStop) continue;
    const mine = attempts.filter((a) => a.routeLegId === leg.id);
    const arrived = mine.find((a) => a.arrivedAtStopAt)?.arrivedAtStopAt;
    const alighted = mine.find((a) => a.alightedAt)?.alightedAt;
    markers.push(
      stopMarker(
        leg,
        "board",
        `승차 ${leg.boardStop.name}${arrived ? ` · 도착 ${formatKstTime(arrived)}` : ""}`,
      ),
      stopMarker(
        leg,
        "alight",
        `하차 ${leg.alightStop.name}${alighted ? ` · ${formatKstTime(alighted)}` : ""}`,
      ),
    );
  }
  return { markers, lines };
}
