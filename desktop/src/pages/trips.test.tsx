import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import type { CommuteTrip } from "../api/client";
import { setToken } from "../auth/token";
import { detail, missedAttempt, route, trip } from "../test/fixtures";
import { BASE, json, renderApp } from "../test/render";

beforeEach(() => setToken("devtoken"));

/** GET /commute-routes, /commute-routes/7, /commute-trips에 답하고, PATCH는 patch 핸들러에 넘긴다. */
function backend(trips: CommuteTrip[], patch?: (req: Request) => Promise<Response>) {
  return async (req: Request) => {
    const url = new URL(req.url);
    if (req.method === "PATCH") return patch!(req);
    if (url.pathname === "/api/v1/commute-routes") return json([route]);
    if (url.pathname === "/api/v1/commute-routes/7") return json(detail);
    if (url.pathname === "/api/v1/commute-trips") return json(trips);
    if (url.pathname.endsWith("/gps-traces")) return json([]);
    throw new Error(`unexpected ${req.method} ${req.url}`);
  };
}

const tripGets = (fetch: { mock: { calls: [Request][] } }) =>
  fetch.mock.calls.filter(
    ([r]) => r.method === "GET" && new URL(r.url).pathname === "/api/v1/commute-trips",
  ).length;

describe("trip list", () => {
  it("shows date, KST times, total duration and result summary per trip", async () => {
    const incomplete: CommuteTrip = {
      ...trip,
      id: 51,
      tripDate: "2026-09-10",
      arrivedDestinationAt: null,
      boardingAttempts: [{ ...missedAttempt, id: 201, tripId: 51 }],
    };
    renderApp("/trips", backend([trip, incomplete]));

    const rows = (await screen.findAllByRole("row")).slice(1);
    expect(rows).toHaveLength(2);
    const first = within(rows[0]!);
    expect(first.getByRole("link", { name: "2026-09-11" })).toHaveAttribute(
      "href",
      "/trips/50?routeId=7&date=2026-09-11",
    );
    expect(first.getByText("07:30:00 → 08:25:30")).toBeInTheDocument();
    expect(first.getByText("55분 30초")).toBeInTheDocument();
    expect(await first.findByText("전 구간 탑승")).toBeInTheDocument();
    expect(first.getByText(/놓친 차 1대/)).toBeInTheDocument();
    expect(await first.findByText("집 → 회사")).toBeInTheDocument();

    const second = within(rows[1]!);
    expect(second.getByText("07:30:00 → —")).toBeInTheDocument();
    expect(second.getByText("0/1 구간 탑승")).toBeInTheDocument();
  });

  it("passes route and date filters to GET /commute-trips", async () => {
    const { fetch } = renderApp("/trips", backend([]));
    expect(await screen.findByText("조건에 맞는 기록이 없습니다.")).toBeInTheDocument();
    await screen.findByRole("option", { name: "집 → 회사" });

    fireEvent.change(screen.getByLabelText("경로"), { target: { value: "7" } });
    fireEvent.change(screen.getByLabelText("시작일"), { target: { value: "2026-09-01" } });
    fireEvent.change(screen.getByLabelText("종료일"), { target: { value: "2026-09-30" } });

    await waitFor(() =>
      expect(fetch.mock.calls.at(-1)![0].url).toBe(
        `${BASE}/api/v1/commute-trips?routeId=7&from=2026-09-01&to=2026-09-30`,
      ),
    );
  });
});

describe("trip detail", () => {
  it("renders the timeline in KST with per-attempt prediction error", async () => {
    const { fetch } = renderApp("/trips/50?routeId=7&date=2026-09-11", backend([trip]));

    const timeline = await screen.findByRole("list", { name: "타임라인" });
    expect(fetch.mock.calls[0]![0].url).toBe(
      `${BASE}/api/v1/commute-trips?routeId=7&from=2026-09-11&to=2026-09-11`,
    );
    await screen.findByRole("heading", { name: "2026-09-11 · 집 → 회사" });

    const items = within(timeline)
      .getAllByRole("listitem")
      .map((li) => li.textContent);
    expect(items).toEqual([
      "07:30:00집 나섬",
      "07:42:30동탄 도착 · GTX-A 동탄 → 수서",
      "07:43:201번째 차 놓침예측 07:43:00 · 실제 − 예측 +20초",
      "07:57:452번째 차 탐 · 출발예측 07:58:00 · 실제 − 예측 −15초",
      "08:18:10수서 하차",
      "08:25:30도착",
    ]);
  });

  it("says so when the trip is not in the filtered list", async () => {
    renderApp("/trips/99?routeId=7&date=2026-09-11", backend([trip]));
    expect(await screen.findByText("기록 99을(를) 찾을 수 없습니다.")).toBeInTheDocument();
  });
});

describe("corrections", () => {
  it("PATCHes changed trip times as UTC and refetches the trips", async () => {
    let body: unknown;
    const { fetch } = renderApp(
      "/trips/50?routeId=7&date=2026-09-11",
      backend([trip], async (req) => {
        expect(new URL(req.url).pathname).toBe("/api/v1/commute-trips/50");
        body = await req.json();
        return json({ ...trip, arrivedDestinationAt: "2026-09-10T23:20:00Z" });
      }),
    );
    const form = await screen.findByRole("form", { name: "trip 시각 보정" });
    const before = tripGets(fetch);

    fireEvent.change(within(form).getByLabelText("도착 (KST)"), {
      target: { value: "2026-09-11T08:20:00" },
    });
    fireEvent.click(within(form).getByRole("button", { name: "시각 저장" }));

    expect(await within(form).findByRole("status")).toHaveTextContent("저장했습니다.");
    expect(body).toEqual({ arrivedDestinationAt: "2026-09-10T23:20:00Z" });
    await waitFor(() => expect(tripGets(fetch)).toBeGreaterThan(before));
  });

  it("shows the backend 400 detail verbatim for an attempt correction", async () => {
    const detailText =
      "alightedAt 2026-09-10T23:30:00Z is outside the trip [2026-09-10T22:30:00Z, 2026-09-10T23:25:30Z]";
    let body: unknown;
    renderApp(
      "/trips/50?routeId=7&date=2026-09-11",
      backend([trip], async (req) => {
        expect(new URL(req.url).pathname).toBe("/api/v1/boarding-attempts/102");
        body = await req.json();
        return json({ title: "Bad Request", status: 400, detail: detailText }, 400);
      }),
    );

    const items = await screen.findAllByRole("listitem");
    const caught = items.find((li) => li.textContent?.includes("2번째 차 · 탐"))!;
    fireEvent.click(within(caught).getByRole("button", { name: "보정" }));
    const form = within(caught).getByRole("form", { name: "2번째 차 보정" });
    fireEvent.change(within(form).getByLabelText("하차 (KST)"), {
      target: { value: "2026-09-11T08:30:00" },
    });
    fireEvent.click(within(form).getByRole("button", { name: "시도 저장" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent(
      `저장하지 못했습니다: ${detailText}`,
    );
    expect(body).toEqual({ alightedAt: "2026-09-10T23:30:00Z" });
  });

  it("does not send a request when nothing changed", async () => {
    const { fetch } = renderApp("/trips/50?routeId=7&date=2026-09-11", backend([trip]));
    const form = await screen.findByRole("form", { name: "trip 시각 보정" });
    fireEvent.click(within(form).getByRole("button", { name: "시각 저장" }));
    expect(within(form).getByRole("alert")).toHaveTextContent("바뀐 값이 없습니다.");
    expect(fetch.mock.calls.some(([r]) => r.method === "PATCH")).toBe(false);
  });
});
