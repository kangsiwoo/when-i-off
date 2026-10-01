import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// leaflet 지도는 jsdom에서 그릴 수 없다. 화면 테스트는 마커·선 데이터만 보는 가짜 지도를 쓴다.
vi.mock("../map/MapView", () => import("./MockMap"));

afterEach(() => {
  cleanup();
  localStorage.clear();
});
