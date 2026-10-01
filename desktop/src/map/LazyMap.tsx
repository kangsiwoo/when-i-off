import { lazy, Suspense } from "react";
import type { MapViewProps } from "./types";

// leaflet은 지도가 있는 화면에서만 받는다 (별도 청크). 테스트는 ./MapView를 vi.mock으로 바꾼다 (jsdom에는 지도 크기가 없다).
const MapView = lazy(() => import("./MapView"));

export function LazyMap(props: MapViewProps) {
  return (
    <Suspense fallback={<div className="map map-loading">지도를 불러오는 중…</div>}>
      <MapView {...props} />
    </Suspense>
  );
}
