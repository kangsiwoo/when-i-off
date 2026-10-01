import type { MapViewProps } from "../map/types";

/** 테스트용 지도 (jsdom에는 크기·캔버스가 없다). 마커·선을 목록으로 그리고 클릭·끌기를 버튼으로 흉내 낸다. */
/** "지도 클릭" 버튼이 찍는 좌표. */
const MAP_CLICK_AT = { lat: 37.25, lng: 127.08 };

export default function MockMap({ markers, lines = [], onMapClick, label }: MapViewProps) {
  return (
    <div role="region" aria-label={label}>
      <button type="button" onClick={() => onMapClick?.(MAP_CLICK_AT)}>
        지도 클릭
      </button>
      <ul aria-label={`${label} 마커`}>
        {markers.map((m) => (
          <li key={m.id} data-kind={m.kind}>
            {m.tooltip ?? m.id}
            {m.onClick && (
              <button type="button" onClick={m.onClick}>
                마커 누르기: {m.tooltip}
              </button>
            )}
            {m.onDragEnd && (
              <button
                type="button"
                onClick={() => m.onDragEnd!({ lat: m.position.lat + 0.001, lng: m.position.lng })}
              >
                끌기: {m.tooltip}
              </button>
            )}
          </li>
        ))}
      </ul>
      <ul aria-label={`${label} 선`}>
        {lines.map((l) => (
          <li key={l.id} data-kind={l.kind}>
            {l.kind} {l.positions.length}점
          </li>
        ))}
      </ul>
    </div>
  );
}
