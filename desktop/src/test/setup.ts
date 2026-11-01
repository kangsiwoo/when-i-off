import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { File as NodeFile } from "node:buffer";
import { afterEach, vi } from "vitest";

// vitest의 jsdom 환경은 File·FormData를 jsdom 것으로 바꾸지만 Request는 Node(undici) 것을 그대로 둔다.
// Node 24+ undici는 Request 본문을 만들 때 FormData 항목의 File 브랜드를 엄격히 검사해 jsdom File을
// 거절한다("webidl.is.File" assert). 업로드가 브라우저처럼 돌도록 둘 다 undici 것으로 맞춘다.
// undici FormData는 전역에 노출돼 있지 않아 Response.formData()가 돌려주는 객체에서 생성자를 꺼낸다.
const nodeForm = await new Response("", {
  headers: { "Content-Type": "application/x-www-form-urlencoded" },
}).formData();
globalThis.FormData = nodeForm.constructor as typeof FormData;
globalThis.File = NodeFile;

// leaflet 지도는 jsdom에서 그릴 수 없다. 화면 테스트는 마커·선 데이터만 보는 가짜 지도를 쓴다.
vi.mock("../map/MapView", () => import("./MockMap"));

afterEach(() => {
  cleanup();
  localStorage.clear();
});
