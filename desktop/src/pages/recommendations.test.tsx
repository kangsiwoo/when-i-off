import { fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setToken } from "../auth/token";
import { detail, recommendationEvaluations, recommendationHistory } from "../test/fixtures";
import { BASE, json, renderApp } from "../test/render";

const NO_EVALUATIONS = { summaries: [], evaluations: [] };

function handler(body: unknown, evaluations: unknown = NO_EVALUATIONS) {
  return (req: Request) => {
    const path = new URL(req.url).pathname;
    if (path.endsWith("/recommendation-history")) return json(body);
    if (path.endsWith("/recommendation-evaluations")) return json(evaluations);
    return json(detail);
  };
}

const historyUrls = (fetch: ReturnType<typeof renderApp>["fetch"]) =>
  fetch.mock.calls.map((c) => c[0].url).filter((u) => u.includes("/recommendation-history"));

describe("recommendation vs actual", () => {
  beforeEach(() => {
    // UTC로는 9/30 22:10이지만 KST로는 10/1이다. 기본 기간은 KST 오늘까지 30일이어야 한다.
    vi.useFakeTimers({ toFake: ["Date"], now: new Date("2026-09-30T22:10:00Z") });
    setToken("devtoken");
  });
  afterEach(() => vi.useRealTimers());

  it("is reachable from the route detail tab", async () => {
    renderApp("/routes/7", handler([]));
    const tab = await screen.findByRole("link", { name: "추천 vs 실제" });
    expect(tab).toHaveAttribute("href", "/routes/7/recommendations");
  });

  it("asks for the last 30 days in KST by default and shows the empty state", async () => {
    const { fetch } = renderApp("/routes/7/recommendations", handler([]));
    expect(await screen.findByText(/추천도 이동 기록도 없습니다/)).toBeInTheDocument();
    expect(historyUrls(fetch)).toEqual([
      `${BASE}/api/v1/commute-routes/7/recommendation-history?from=2026-09-02&to=2026-10-01`,
    ]);
    expect(screen.getByRole("button", { name: "최근 30일" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("presets and the range in the address change the request", async () => {
    const { fetch } = renderApp(
      "/routes/7/recommendations?from=2026-09-21&to=2026-09-23",
      handler(recommendationHistory),
    );
    await screen.findByRole("table", { name: "날짜별 추천과 실제" });
    expect(historyUrls(fetch)).toContain(
      `${BASE}/api/v1/commute-routes/7/recommendation-history?from=2026-09-21&to=2026-09-23`,
    );
    fireEvent.click(screen.getByRole("button", { name: "최근 7일" }));
    await vi.waitFor(() =>
      expect(historyUrls(fetch)).toContain(
        `${BASE}/api/v1/commute-routes/7/recommendation-history?from=2026-09-25&to=2026-10-01`,
      ),
    );
  });

  it("does not request a range that ends before it starts", async () => {
    const { fetch } = renderApp(
      "/routes/7/recommendations?from=2026-09-23&to=2026-09-21",
      handler(recommendationHistory),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "종료일이 시작일보다 앞설 수 없습니다",
    );
    expect(historyUrls(fetch)).toEqual([]);
  });

  it("shows per-date rows with differences, lateness and results", async () => {
    renderApp(
      "/routes/7/recommendations?from=2026-09-21&to=2026-09-23",
      handler(recommendationHistory),
    );
    const table = await screen.findByRole("table", { name: "날짜별 추천과 실제" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(4); // 9/21 두 줄 + 9/22 + 9/23

    const first = within(rows[0]!);
    expect(first.getByText("2026-09-21")).toBeInTheDocument();
    expect(first.getByText("07:24")).toBeInTheDocument(); // 추천 v1 (KST)
    expect(first.getByText("07:30")).toBeInTheDocument(); // 추천 v2
    // 표본 수(#86)는 있는 추천에만 붙는다 — v1 행에는 없다.
    expect(rows[0]).toHaveTextContent("(88%, 여유 7분, 표본 7)");
    expect(rows[0]).toHaveTextContent("(91%, 여유 11분)");
    expect(first.getByText("07:27")).toBeInTheDocument(); // 실제 출발
    // v1 대비 3분 10초 늦게, v2 대비 2분 50초 일찍 나섰다.
    expect(rows[0]).toHaveTextContent("v1+3분");
    expect(rows[0]).toHaveTextContent("v2−3분");
    expect(first.getByText("09:00")).toBeInTheDocument(); // 목표 도착
    expect(first.getByText("08:58")).toBeInTheDocument();
    expect(first.getByText(/제시간 −2분/)).toBeInTheDocument();
    expect(first.getByText("전 구간 탑승")).toBeInTheDocument();
    expect(first.getByText(/놓친 차 1대/)).toBeInTheDocument();

    // 같은 날 두 번째 trip: 날짜·추천 칸은 비우고, 도착 기록이 없다.
    const second = within(rows[1]!);
    expect(second.queryByText("2026-09-21")).toBeNull();
    expect(second.getByText("18:00")).toBeInTheDocument();
    expect(second.getByText("도착 기록 없음")).toBeInTheDocument();
    expect(second.getByText("탑승 못 한 구간 있음")).toBeInTheDocument();

    // 추천만 있는 날.
    const third = within(rows[2]!);
    expect(third.getByText("07:26")).toBeInTheDocument();
    expect(third.getByText("기록 없음")).toBeInTheDocument();

    // trip만 있는 날: 목표가 없어 지각 판정이 없다.
    const fourth = within(rows[3]!);
    expect(fourth.getByText("추천 없음")).toBeInTheDocument();
    expect(fourth.getByText("07:35")).toBeInTheDocument();
    expect(fourth.queryByText(/지각|제시간/)).toBeNull();
  });

  it("marks late arrivals with a label, not color alone", async () => {
    const late = structuredClone(recommendationHistory);
    late[0]!.trips[0]!.arrivedDestinationAt = "2026-09-21T00:04:30Z";
    renderApp("/routes/7/recommendations?from=2026-09-21&to=2026-09-23", handler(late));
    const table = await screen.findByRole("table", { name: "날짜별 추천과 실제" });
    expect(within(table).getByText(/지각 \+5분/)).toBeInTheDocument();
  });

  it("titles both charts and keeps the legend in text", async () => {
    renderApp(
      "/routes/7/recommendations?from=2026-09-21&to=2026-09-23",
      handler(recommendationHistory),
    );
    expect(
      await screen.findByRole("heading", { name: "추천 출발 vs 실제 출발" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "buffer 추이" })).toBeInTheDocument();
    const legends = screen.getAllByRole("list", { name: "범례" });
    expect(within(legends[0]!).getByText("실제 출발")).toBeInTheDocument();
    expect(within(legends[0]!).getByText("추천 v1")).toBeInTheDocument();
    expect(within(legends[0]!).getByText("추천 v2")).toBeInTheDocument();
  });

  it("summarizes each model version above the charts with stat tiles", async () => {
    const { fetch } = renderApp(
      "/routes/7/recommendations?from=2026-09-21&to=2026-09-23",
      handler(recommendationHistory, recommendationEvaluations),
    );
    const section = await screen.findByRole("region", { name: "버전별 성과" });
    expect(fetch.mock.calls.map((c) => c[0].url)).toContain(
      `${BASE}/api/v1/commute-routes/7/recommendation-evaluations?from=2026-09-21&to=2026-09-23`,
    );
    // 버전 순(v1 → v2), 차트보다 위.
    const groups = within(section).getAllByRole("group");
    expect(groups.map((g) => g.getAttribute("aria-labelledby"))).toEqual(["eval-v1", "eval-v2"]);
    const chartTitle = screen.getByRole("heading", { name: "추천 출발 vs 실제 출발" });
    expect(
      section.compareDocumentPosition(chartTitle) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    const v1 = within(groups[0]!);
    expect(v1.getByText("6일")).toBeInTheDocument();
    expect(v1.getByText("40%")).toBeInTheDocument();
    expect(v1.getByText("2/5일 (도착 기록 있는 날)")).toBeInTheDocument();
    expect(v1.getByText("−36분")).toBeInTheDocument();
    expect(v1.getByText("8분 52초")).toBeInTheDocument();
    expect(v1.getByText("67%")).toBeInTheDocument();
    expect(v1.queryByText("표본 적음")).toBeNull();

    const v2 = within(groups[1]!);
    expect(v2.getByText("표본 적음")).toBeInTheDocument();
    expect(v2.getByText("도착 기록 없음")).toBeInTheDocument();
    expect(v2.getAllByText("—")).toHaveLength(2); // 지각률, 평균 출발 차이
    expect(v2.getByText("45초")).toBeInTheDocument();
  });

  it("tells to run evaluate when days that could be evaluated have no evaluations", async () => {
    renderApp(
      "/routes/7/recommendations?from=2026-09-21&to=2026-09-23",
      handler(recommendationHistory),
    );
    const section = await screen.findByRole("region", { name: "버전별 성과" });
    expect(section).toHaveTextContent("평가가 아직 없습니다");
    expect(section).toHaveTextContent(
      "uv run wio-analytics evaluate --from 2026-09-21 --to 2026-09-23",
    );
    expect(section).toHaveTextContent("03:00");
    // 요약이 없어도 차트와 표는 그대로다.
    expect(screen.getByRole("table", { name: "날짜별 추천과 실제" })).toBeInTheDocument();
  });

  it("says there is nothing to evaluate when no day has both a recommendation and a trip", async () => {
    const apart = recommendationHistory.filter((d) => d.date !== "2026-09-21");
    renderApp("/routes/7/recommendations?from=2026-09-21&to=2026-09-23", handler(apart));
    const section = await screen.findByRole("region", { name: "버전별 성과" });
    expect(section).toHaveTextContent("평가할 날이 없습니다");
    expect(section).not.toHaveTextContent("wio-analytics evaluate");
  });
});
