import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import type {
  CommuteRouteDetail,
  RouteLeg,
  TrafficSignal,
  TransitLine,
  TransitStop,
} from "../api/client";
import { setToken } from "../auth/token";
import { detail, route, trip } from "../test/fixtures";
import { json, renderApp } from "../test/render";

beforeEach(() => setToken("devtoken"));

const line: TransitLine = detail.legs.find((l) => l.legType === "TRANSIT")!.transitLine!;
const stop = (id: number, name: string, lat: number, lng: number): TransitStop => ({
  id,
  mode: "GTX",
  name,
  lat,
  lng,
  createdAt: "2026-09-10T00:00:00Z",
  distanceM: 120,
});
const dongtan = stop(4, "동탄", 37.2, 127.09);
const suseo = stop(5, "수서", 37.48, 127.1);
const signal: TrafficSignal = {
  id: 9,
  lat: 37.2,
  lng: 127.08,
  name: "동탄역 사거리",
  createdAt: "2026-09-10T00:00:00Z",
  distanceM: 40,
};

type Handler = (req: Request, url: URL) => Response | undefined | Promise<Response | undefined>;

/** 기본 GET 응답 + 테스트별 handler. handler가 undefined를 주면 기본 응답으로. */
function backend(routeDetail: CommuteRouteDetail, handler: Handler = () => undefined) {
  return async (req: Request) => {
    const url = new URL(req.url);
    const custom = await handler(req, url);
    if (custom) return custom;
    if (req.method === "GET") {
      if (url.pathname === "/api/v1/commute-routes") return json([routeDetail.route]);
      if (url.pathname === `/api/v1/commute-routes/${routeDetail.route.id}`)
        return json(routeDetail);
      if (url.pathname === "/api/v1/transit-lines/search") return json([line]);
      if (url.pathname === "/api/v1/transit-stops/nearby") return json([dongtan, suseo]);
      if (url.pathname === "/api/v1/traffic-signals/nearby") return json([signal]);
    }
    throw new Error(`unexpected ${req.method} ${req.url}`);
  };
}

const calls = (fetch: { mock: { calls: [Request][] } }, method: string, path: string) =>
  fetch.mock.calls.filter(([r]) => r.method === method && new URL(r.url).pathname === path);

const walkFixture = detail.legs.find((l) => l.legType === "WALK")!;
const transitFixture = detail.legs.find((l) => l.legType === "TRANSIT")!;
const lastWalk: RouteLeg = {
  ...walkFixture,
  id: 13,
  seqOrder: 3,
  startLat: 37.48,
  startLng: 127.1,
  endLat: 37.49,
  endLng: 127.1,
  plannedDistanceM: 1100,
  signalCrossings: [],
};
const fullDetail: CommuteRouteDetail = { route, legs: [walkFixture, transitFixture, lastWalk] };

describe("route create", () => {
  it("places origin/destination by map clicks, POSTs and opens the leg editor", async () => {
    let posted: unknown;
    const created = {
      ...route,
      id: 21,
      name: "새 출근",
      originLat: 37.25,
      originLng: 127.08,
      destinationLat: 37.251,
      destinationLng: 127.08,
    };
    const { router } = renderApp(
      "/routes/new",
      backend({ route: created, legs: [] }, async (req, url) => {
        if (req.method === "POST" && url.pathname === "/api/v1/commute-routes") {
          posted = await req.json();
          return json(created, 201);
        }
      }),
    );

    fireEvent.change(await screen.findByLabelText("이름"), { target: { value: " 새 출근 " } });
    fireEvent.click(screen.getByRole("button", { name: "만들고 구간 편집" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("지도에서 출발과 도착을 찍으세요.");

    fireEvent.click(await screen.findByRole("button", { name: "지도 클릭" })); // 출발
    // 다음 클릭은 도착으로 넘어간다. 도착은 끌어서 옮긴다.
    fireEvent.click(screen.getByRole("button", { name: "지도 클릭" }));
    fireEvent.click(screen.getByRole("button", { name: "끌기: 도착" }));
    fireEvent.change(screen.getByLabelText("방향"), { target: { value: "TO_HOME" } });
    // 기본 목표 도착 시각과 추천할 날 (#68). 평일은 기본으로 켜져 있다.
    fireEvent.change(screen.getByLabelText("목표 도착 시각 (KST)"), { target: { value: "18:40" } });
    fireEvent.click(screen.getByLabelText("토요일"));
    fireEvent.click(screen.getByRole("button", { name: "만들고 구간 편집" }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/routes/21/edit"));
    expect(posted).toEqual({
      name: "새 출근",
      direction: "TO_HOME",
      originLat: 37.25,
      originLng: 127.08,
      destinationLat: 37.251,
      destinationLng: 127.08,
      isActive: true,
      defaultTargetArrivalTime: "18:40",
      defaultTargetDayTypes: ["WEEKDAY", "SATURDAY"],
    });
    // 구간이 없는 경로는 출발 → 도착 도보 한 구간으로 시작한다 (직선거리 미리 채움).
    expect(await screen.findByLabelText("계획 거리 (m)")).toHaveValue(111);
  });
});

describe("route create: default target", () => {
  it("refuses to create a route with no day selected", async () => {
    const { fetch } = renderApp("/routes/new", backend({ route, legs: [] }));
    fireEvent.change(await screen.findByLabelText("이름"), { target: { value: "x" } });
    fireEvent.click(await screen.findByRole("button", { name: "지도 클릭" }));
    fireEvent.click(screen.getByRole("button", { name: "지도 클릭" }));
    fireEvent.click(screen.getByLabelText("평일"));
    fireEvent.click(screen.getByRole("button", { name: "만들고 구간 편집" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("추천할 날을 하나 이상 고르세요.");
    expect(calls(fetch, "POST", "/api/v1/commute-routes")).toHaveLength(0);
  });
});

describe("leg editor", () => {
  it("checks the server rules before sending and PUTs ids in screen order", async () => {
    let body: unknown;
    const { fetch } = renderApp(
      "/routes/7/edit",
      backend(detail, async (req) => {
        if (req.method === "PUT") {
          body = await req.json();
          return json(fullDetail);
        }
      }),
    );

    // 픽스처는 WALK → TRANSIT으로 끝난다: 서버 규칙(끝은 WALK)에 걸리므로 보내지 않는다.
    fireEvent.click(await screen.findByRole("button", { name: "구간 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "경로는 도보 구간으로 시작하고 도보 구간으로 끝나야 합니다.",
    );
    expect(calls(fetch, "PUT", "/api/v1/commute-routes/7/legs")).toHaveLength(0);

    fireEvent.click(screen.getByRole("button", { name: "+ 도보" }));
    expect(screen.getByText("저장 안 된 변경")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "구간 저장" }));

    expect(await screen.findByRole("status")).toHaveTextContent("저장했습니다.");
    expect(body).toEqual({
      legs: [
        {
          id: 11,
          seqOrder: 1,
          legType: "WALK",
          startLat: 37.2,
          startLng: 127.07,
          endLat: 37.2,
          endLng: 127.09,
          plannedDistanceM: 650,
        },
        {
          id: 12,
          seqOrder: 2,
          legType: "TRANSIT",
          transitLineId: 3,
          boardStopId: 4,
          alightStopId: 5,
          plannedTravelSec: 1200,
        },
        {
          seqOrder: 3,
          legType: "WALK",
          // 앞 대중교통의 하차 정류장(수서) → 경로 도착
          startLat: 37.48,
          startLng: 127.1,
          endLat: 37.49,
          endLng: 127.1,
          plannedDistanceM: 1112,
        },
      ],
    });
    expect(screen.queryByText("저장 안 된 변경")).not.toBeInTheDocument();
  });

  it("shows the 409 detail verbatim when a measured leg would be removed", async () => {
    const detail409 =
      "legs [12] have recorded trips and cannot be removed or retyped; send them back with their id";
    renderApp(
      "/routes/7/edit",
      backend(fullDetail, (req) => {
        if (req.method === "PUT")
          return json({ status: 409, title: "Conflict", detail: detail409 }, 409);
      }),
    );
    const cards = await screen.findAllByRole("listitem", { name: /^구간 \d$/ });
    expect(cards).toHaveLength(3);
    // 대중교통과 뒤 도보를 지운다 → 남은 도보 하나는 규칙상 맞지만 서버가 409.
    fireEvent.click(within(cards[2]!).getByRole("button", { name: "삭제" }));
    fireEvent.click(within(cards[1]!).getByRole("button", { name: "삭제" }));
    fireEvent.click(screen.getByRole("button", { name: "구간 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(`저장하지 못했습니다: ${detail409}`);
  });

  it("drags a WALK endpoint and re-fills the straight-line distance unless edited", async () => {
    renderApp("/routes/7/edit", backend({ route, legs: [] }));
    const distance = await screen.findByLabelText("계획 거리 (m)");
    const before = Number((distance as HTMLInputElement).value);
    fireEvent.click(await screen.findByRole("button", { name: /끌기: 구간 1 도보 끝/ }));
    await waitFor(() => expect(Number((distance as HTMLInputElement).value)).not.toBe(before));

    fireEvent.change(distance, { target: { value: "5000" } });
    fireEvent.click(screen.getByRole("button", { name: /끌기: 구간 1 도보 끝/ }));
    expect(distance).toHaveValue(5000);
    fireEvent.click(screen.getByRole("button", { name: "직선거리로" }));
    expect(distance).not.toHaveValue(5000);
  });

  it("builds a TRANSIT leg: line search, nearby stops, snapping the walk endpoints", async () => {
    let body: { legs: unknown[] } | undefined;
    const { fetch } = renderApp(
      "/routes/7/edit",
      backend({ route, legs: [] }, async (req) => {
        if (req.method === "PUT") {
          body = (await req.json()) as { legs: unknown[] };
          return json(fullDetail);
        }
      }),
    );

    fireEvent.click(await screen.findByRole("button", { name: "뒤에 대중교통 넣기" }));
    const transit = screen.getByRole("listitem", { name: "구간 2" });
    const t = within(transit);
    fireEvent.change(t.getByLabelText("노선 검색"), { target: { value: "GTX" } });
    fireEvent.click(t.getByRole("button", { name: "검색" }));
    fireEvent.click(await t.findByRole("button", { name: "GTX-A" }));
    expect(t.getByText("GTX-A (GTX)")).toBeInTheDocument();

    fireEvent.click(t.getByRole("button", { name: "앞 도보 끝 근처" }));
    fireEvent.click(await t.findByRole("button", { name: "동탄" }));
    // 하차는 지도에서 위치를 찍어 근처를 찾는다.
    fireEvent.click(t.getByRole("button", { name: "하차 정류장 위치를 지도에서 찍기" }));
    fireEvent.click(screen.getByRole("button", { name: "지도 클릭" }));
    fireEvent.click(await t.findByRole("button", { name: "수서" }));
    fireEvent.change(t.getByLabelText("계획 소요 (분)"), { target: { value: "21" } });

    const nearby = calls(fetch, "GET", "/api/v1/transit-stops/nearby").map(
      ([r]) => new URL(r.url).searchParams,
    );
    expect(nearby[0]!.get("mode")).toBe("GTX");
    expect(nearby[1]!.get("lat")).toBe("37.25");

    fireEvent.click(screen.getByRole("button", { name: "구간 저장" }));
    await screen.findByRole("status");
    expect(body!.legs).toEqual([
      {
        seqOrder: 1,
        legType: "WALK",
        startLat: 37.2,
        startLng: 127.07,
        endLat: 37.2, // 동탄으로 붙음
        endLng: 127.09,
        plannedDistanceM: 1771,
      },
      {
        seqOrder: 2,
        legType: "TRANSIT",
        transitLineId: 3,
        boardStopId: 4,
        alightStopId: 5,
        plannedTravelSec: 1260,
      },
      {
        seqOrder: 3,
        legType: "WALK",
        startLat: 37.48, // 수서로 붙음
        startLng: 127.1,
        endLat: 37.49,
        endLng: 127.1,
        plannedDistanceM: 1112,
      },
    ]);
  });

  it("reorders legs and reports the alternation rule", async () => {
    renderApp("/routes/7/edit", backend(fullDetail));
    const cards = await screen.findAllByRole("listitem", { name: /^구간 \d$/ });
    fireEvent.click(within(cards[1]!).getByRole("button", { name: "아래로" }));
    fireEvent.click(screen.getByRole("button", { name: "구간 저장" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("경로는 도보 구간으로 시작하고 도보 구간으로 끝나야 합니다.");
    expect(alert).toHaveTextContent(
      "도보와 대중교통이 번갈아 와야 합니다 (구간 1·2이 둘 다 도보).",
    );
  });
});

describe("signal crossings", () => {
  it("adds a nearby signal and a new map-clicked one, then PUTs the crossings", async () => {
    let body: unknown;
    let created: unknown;
    renderApp(
      "/routes/7/edit",
      backend(fullDetail, async (req, url) => {
        if (req.method === "PUT" && url.pathname === "/api/v1/route-legs/11/signal-crossings") {
          body = await req.json();
          return json({ ...walkFixture, signalCrossings: [] });
        }
        if (req.method === "POST" && url.pathname === "/api/v1/traffic-signals") {
          created = await req.json();
          return json({ ...signal, id: 30, name: "새 교차로", distanceM: undefined }, 201);
        }
      }),
    );

    const first = await screen.findByRole("listitem", { name: "구간 1" });
    // 처음에는 아무 구간도 고르지 않은 상태 (지도는 경로 전체). 구간을 누르면 교차로 편집이 열린다.
    expect(within(first).queryByText("건너는 신호등")).not.toBeInTheDocument();
    fireEvent.click(within(first).getByText("도보"));
    const c = within(first);
    expect(await c.findByText("건너는 신호등")).toBeInTheDocument();
    // 픽스처 crossing(9, nt)이 이미 있다. 근처 목록에는 안 나온다.
    expect(await c.findByText("동탄역 사거리")).toBeInTheDocument();
    fireEvent.change(c.getByLabelText("교차로 1 접근 방향"), { target: { value: "sw" } });

    fireEvent.click(c.getByRole("button", { name: "지도에서 교차로 등록" }));
    fireEvent.click(screen.getByRole("button", { name: "지도 클릭" }));
    fireEvent.change(c.getByLabelText("교차로 이름"), { target: { value: "새 교차로" } });
    fireEvent.click(c.getByRole("button", { name: "등록하고 추가" }));
    expect(await c.findByText("새 교차로")).toBeInTheDocument();
    expect(created).toEqual({ lat: 37.25, lng: 127.08, name: "새 교차로" });

    fireEvent.click(c.getByRole("button", { name: "교차로 저장" }));
    expect(await c.findByRole("status")).toHaveTextContent("저장했습니다.");
    expect(body).toEqual({
      crossings: [
        { trafficSignalId: 9, approachDir: "sw", signalKind: "Pd", seqOrder: 1 },
        { trafficSignalId: 30, approachDir: "nt", signalKind: "Pd", seqOrder: 2 },
      ],
    });
  });
});

describe("trip GPS overlay", () => {
  it("draws the track in recordedAt order with start/end and the route's stops", async () => {
    const { fetch } = renderApp(`/trips/${trip.id}?routeId=7&date=${trip.tripDate}`, (req) => {
      const url = new URL(req.url);
      if (url.pathname === "/api/v1/commute-trips") return json([trip]);
      if (url.pathname === "/api/v1/commute-routes/7") return json(detail);
      if (url.pathname === `/api/v1/commute-trips/${trip.id}/gps-traces`)
        return json([
          { recordedAt: "2026-09-10T22:30:00Z", lat: 37.2, lng: 127.07 },
          { recordedAt: "2026-09-10T22:31:00Z", lat: 37.201, lng: 127.072, accuracyM: 8 },
          { recordedAt: "2026-09-10T22:32:00Z", lat: 37.202, lng: 127.075 },
        ]);
      throw new Error(`unexpected ${req.url}`);
    });

    expect(await screen.findByText(/포인트 3개 · 07:30:00 → 07:32:00/)).toBeInTheDocument();
    const lines = await screen.findByRole("list", { name: "GPS 트랙 지도 선" });
    expect(within(lines).getByText("gps 3점")).toBeInTheDocument();
    const markers = screen.getByRole("list", { name: "GPS 트랙 지도 마커" });
    expect(within(markers).getByText("GPS 시작 07:30:00")).toBeInTheDocument();
    expect(within(markers).getByText("GPS 끝 07:32:00")).toBeInTheDocument();
    expect(within(markers).getByText(/^승차 동탄/)).toBeInTheDocument();
    expect(calls(fetch, "GET", `/api/v1/commute-trips/${trip.id}/gps-traces`)).toHaveLength(1);
  });
});

describe("signal cycles (#78)", () => {
  const publicRow = {
    id: 3,
    dayType: "WEEKDAY" as const,
    timeBandStart: "07:00:00",
    timeBandEnd: "10:00:00",
    cycleDurationSec: 140,
    redDurationSec: 100,
    source: "PUBLIC_API" as const,
    createdAt: "2026-10-03T00:00:00Z",
  };
  const userRow = {
    ...publicRow,
    id: 4,
    timeBandStart: "08:00:00",
    timeBandEnd: "09:00:00",
    cycleDurationSec: 120,
    redDurationSec: 60,
    source: "USER_OBSERVED" as const,
  };

  async function openCycles() {
    const first = await screen.findByRole("listitem", { name: "구간 1" });
    fireEvent.click(within(first).getByText("도보"));
    const c = within(first);
    await c.findByText("동탄역 사거리");
    fireEvent.click(c.getByRole("button", { name: "신호 주기" }));
    const p = within(await c.findByRole("region", { name: "동탄역 사거리 신호 주기" }));
    await p.findByRole("button", { name: "주기 저장" });
    return p;
  }

  it("shows other sources read-only, edits user rows with a preview and PUTs them", async () => {
    let body: unknown;
    renderApp(
      "/routes/7/edit",
      backend(fullDetail, async (req, url) => {
        if (url.pathname !== "/api/v1/traffic-signals/9/cycles") return undefined;
        if (req.method === "GET") return json([publicRow, userRow]);
        if (req.method === "PUT") {
          body = await req.json();
          return json([
            publicRow,
            { ...userRow, id: 8, cycleDurationSec: 150, redDurationSec: 110 },
          ]);
        }
      }),
    );
    const p = await openCycles();

    const table = p.getByRole("table", { name: "다른 출처 주기 (읽기 전용)" });
    expect(within(table).getByText("공공 데이터")).toBeInTheDocument();
    expect(within(table).getByText("07:00–10:00")).toBeInTheDocument();
    expect(within(table).queryByRole("textbox")).not.toBeInTheDocument();

    expect(p.getByLabelText("1행 주기(초)")).toHaveValue(120);
    expect(p.getByText("15.0초")).toBeInTheDocument(); // 60² / 240
    fireEvent.change(p.getByLabelText("1행 주기(초)"), { target: { value: "150" } });
    fireEvent.change(p.getByLabelText("1행 적색(초)"), { target: { value: "110" } });
    expect(p.getByText("40.3초")).toBeInTheDocument();
    expect(p.getByText(/적색 73%/)).toBeInTheDocument();

    fireEvent.click(p.getByRole("button", { name: "주기 저장" }));
    expect(await p.findByRole("status")).toHaveTextContent("저장했습니다.");
    expect(body).toEqual({
      cycles: [
        {
          dayType: "WEEKDAY",
          timeBandStart: "08:00",
          timeBandEnd: "09:00",
          cycleDurationSec: 150,
          redDurationSec: 110,
        },
      ],
    });
  });

  it("blocks invalid rows client-side and shows a server 400 detail verbatim", async () => {
    const detail400 =
      "cycles[0]: redDurationSec must be greater than 0 and less than cycleDurationSec (90), got 95";
    const { fetch } = renderApp(
      "/routes/7/edit",
      backend(fullDetail, (req, url) => {
        if (url.pathname !== "/api/v1/traffic-signals/9/cycles") return undefined;
        if (req.method === "GET") return json([]);
        return json({ title: "Bad Request", status: 400, detail: detail400 }, 400);
      }),
    );
    const p = await openCycles();
    expect(p.getByText(/기본값\(주기 120초, 적색 90초, 평균 대기 33.8초\)/)).toBeInTheDocument();

    fireEvent.click(p.getByRole("button", { name: "+ 시간대" }));
    fireEvent.change(p.getByLabelText("1행 주기(초)"), { target: { value: "90" } });
    fireEvent.change(p.getByLabelText("1행 적색(초)"), { target: { value: "95" } });
    fireEvent.click(p.getByRole("button", { name: "주기 저장" }));
    expect(p.getByRole("alert")).toHaveTextContent(
      "1행: 적색(95초)은 주기(90초)보다 짧아야 합니다.",
    );
    expect(calls(fetch, "PUT", "/api/v1/traffic-signals/9/cycles")).toHaveLength(0);

    // 서버 규칙이 바뀌어 클라이언트가 놓친 경우를 흉내 낸다: 맞는 값으로 보내고 서버가 400을 준다.
    fireEvent.change(p.getByLabelText("1행 적색(초)"), { target: { value: "60" } });
    fireEvent.click(p.getByRole("button", { name: "주기 저장" }));
    expect(await p.findByRole("alert")).toHaveTextContent(`저장하지 못했습니다: ${detail400}`);
  });
});
