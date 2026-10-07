import type { LatLng } from "./geo";
import type { LineTone } from "./lineTone";

export type MarkerKind =
  | "origin"
  | "destination"
  | "walk-start"
  | "walk-end"
  | "stop"
  | "candidate"
  | "signal"
  | "signal-crossed"
  | "gps-start"
  | "gps-end"
  | "gps-point";

export interface MapMarker {
  id: string;
  position: LatLng;
  kind: MarkerKind;
  /** 핀 안의 짧은 글자 (예: 구간 번호). */
  text?: string;
  /** 마우스를 올리면 보이는 설명. 화면 읽기 프로그램용 이름도 겸한다. */
  tooltip?: string;
  selected?: boolean;
  /** 있으면 끌 수 있다. */
  onDragEnd?: (p: LatLng) => void;
  onClick?: () => void;
}

export interface MapLine {
  id: string;
  positions: LatLng[];
  kind: "walk" | "transit" | "gps";
  /** 대중교통 선의 노선색 (`lineTone`). 없으면 잉크색. */
  tone?: LineTone;
  selected?: boolean;
}

export interface MapViewProps {
  markers: MapMarker[];
  lines?: MapLine[];
  onMapClick?: (p: LatLng) => void;
  /** 화면을 맞출 점들. 없으면 마커·선 전부. */
  fitTo?: LatLng[];
  /** 바뀔 때만 화면을 다시 맞춘다. */
  fitKey?: string;
  /** 지도 클릭으로 무언가를 찍는 중이면 십자 커서. */
  picking?: boolean;
  label: string;
}
