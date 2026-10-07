/**
 * Leaflet 지도 (#64). 이 파일만 leaflet을 import하고 `LazyMap`이 lazy로 읽으므로 leaflet(JS·CSS)은 지도가 있는
 * 화면에서만 따로 받는 청크에 들어간다. 화면 쪽은 마커·선을 데이터로 넘기고 이벤트만 받는다.
 */
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { useEffect, useMemo } from "react";
import {
  MapContainer,
  Marker,
  Polyline,
  TileLayer,
  Tooltip,
  useMap,
  useMapEvents,
} from "react-leaflet";
import { boundsOf, type LatLng } from "./geo";
import type { MapLine, MapMarker, MapViewProps } from "./types";

// OSM 타일 사용 정책: 출처 표기 필수, 대량·오프라인 사용 금지. 1인 관리 화면이라 기본 타일 서버를 그대로 쓴다.
const TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
const SEOUL: [number, number] = [37.5665, 126.978];

// 선의 색은 CSS(index.css `.map-line-*`)가 디자인 토큰으로 칠한다 — 테마가 바뀌어도 따라가게. 여기서는 굵기와 모양만.
// 대중교통은 노선색 실선(소개 페이지 노선도처럼 굵게), 도보는 잉크 점선, GPS는 잉크 실선이다.
const LINE_STYLE: Record<MapLine["kind"], L.PolylineOptions> = {
  walk: { weight: 4, opacity: 1, dashArray: "2 8", lineCap: "round" },
  transit: { weight: 7, opacity: 1, lineCap: "butt" },
  gps: { weight: 3, opacity: 0.9 },
};

const lineClass = (line: MapLine) =>
  `map-line map-line-${line.kind}${line.kind === "transit" ? ` map-line-${line.tone ?? "ink"}` : ""}`;

function icon(marker: MapMarker) {
  // 기본 마커 이미지는 번들러에서 경로가 깨지므로 CSS로 그린 divIcon을 쓴다 (index.css .map-pin).
  return L.divIcon({
    className: `map-pin map-pin-${marker.kind}${marker.selected ? " map-pin-selected" : ""}`,
    html: `<span>${escapeHtml(marker.text ?? "")}</span>`,
    iconSize: marker.kind === "gps-point" ? [8, 8] : [22, 22],
  });
}

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

function ClickHandler({ onClick }: { onClick?: (p: LatLng) => void }) {
  useMapEvents({
    click(e) {
      onClick?.({ lat: e.latlng.lat, lng: e.latlng.lng });
    },
  });
  return null;
}

/** `fitKey`가 바뀔 때만 화면을 맞춘다 — 마커를 끌 때마다 지도가 움직이지 않게. */
function FitBounds({ points, fitKey }: { points: LatLng[]; fitKey: string }) {
  const map = useMap();
  useEffect(() => {
    const b = boundsOf(points);
    if (!b) return;
    if (b[0][0] === b[1][0] && b[0][1] === b[1][1]) map.setView(b[0], 16);
    else map.fitBounds(b, { padding: [32, 32], maxZoom: 17 });
    // points는 fitKey가 바뀔 때의 값만 쓴다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey, map]);
  return null;
}

export default function MapView({
  markers,
  lines = [],
  onMapClick,
  fitTo,
  fitKey = "",
  picking = false,
  label,
}: MapViewProps) {
  const fitPoints = useMemo(
    () => fitTo ?? [...markers.map((m) => m.position), ...lines.flatMap((l) => l.positions)],
    [fitTo, markers, lines],
  );
  return (
    <div className={`map${picking ? " map-picking" : ""}`} role="region" aria-label={label}>
      <MapContainer center={SEOUL} zoom={11} scrollWheelZoom className="map-canvas">
        <TileLayer url={TILE_URL} attribution={ATTRIBUTION} maxZoom={19} />
        <ClickHandler onClick={onMapClick} />
        <FitBounds points={fitPoints} fitKey={fitKey} />
        {lines.map((line) => (
          <Polyline
            // className은 Leaflet이 선을 만들 때만 붙이므로, 노선색이 바뀌면 다시 만든다.
            key={`${line.id}:${line.tone ?? ""}`}
            positions={line.positions.map((p) => [p.lat, p.lng] as [number, number])}
            // 선은 보기만 한다. 클릭이 선에 먹히지 않고 지도로 가야 "지도에서 찍기"가 선 위에서도 된다.
            interactive={false}
            pathOptions={{
              ...LINE_STYLE[line.kind],
              className: lineClass(line),
              ...(line.selected ? { weight: (LINE_STYLE[line.kind].weight ?? 4) + 3 } : {}),
            }}
          />
        ))}
        {markers.map((m) => (
          <Marker
            key={m.id}
            position={[m.position.lat, m.position.lng]}
            icon={icon(m)}
            draggable={!!m.onDragEnd}
            // 고른 구간 > GPS 시작/끝 > 나머지 (정류장 마커가 GPS 끝점을 가리지 않게).
            zIndexOffset={m.selected ? 1000 : m.kind.startsWith("gps-") ? 500 : 0}
            eventHandlers={{
              dragend: (e) => {
                const p = (e.target as L.Marker).getLatLng();
                m.onDragEnd?.({ lat: p.lat, lng: p.lng });
              },
              click: () => m.onClick?.(),
            }}
          >
            {m.tooltip && <Tooltip direction="top">{m.tooltip}</Tooltip>}
          </Marker>
        ))}
      </MapContainer>
    </div>
  );
}
