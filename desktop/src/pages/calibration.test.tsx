import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { setToken } from "../auth/token";
import { calibration, detail } from "../test/fixtures";
import { BASE, json, renderApp } from "../test/render";

function handler(body: unknown) {
  return (req: Request) => (req.url.endsWith("/calibration") ? json(body) : json(detail));
}

describe("route calibration", () => {
  it("is reachable from the route detail tab", async () => {
    setToken("devtoken");
    renderApp("/routes/7", handler(calibration));
    const tab = await screen.findByRole("link", { name: "보정 상태" });
    expect(tab).toHaveAttribute("href", "/routes/7/calibration");
  });

  it("shows walking rows with the low-confidence badge and the value recommend would use", async () => {
    setToken("devtoken");
    const { fetch } = renderApp("/routes/7/calibration", handler(calibration));

    const walk = within(await screen.findByRole("article", { name: "구간 1" }));
    expect(fetch.mock.calls.map((c) => c[0].url)).toContain(
      `${BASE}/api/v1/commute-routes/7/calibration`,
    );
    expect(walk.getByText("1.40 ± 0.10 m/s")).toBeInTheDocument();
    expect(walk.getByText(/3개/)).toBeInTheDocument();
    expect(walk.getByText("신뢰도 낮음")).toBeInTheDocument();
    // 구간 행이 3개라 전역 행(30개)이 쓰인다.
    expect(walk.getByText("1.25 ± 0.20 m/s", { selector: "strong" })).toBeInTheDocument();
    expect(walk.getByText(/전체 도보 기록, 샘플 30개/)).toBeInTheDocument();
  });

  it("shows prediction bands in a table, marks low-sample rows, and an empty travel-time table", async () => {
    setToken("devtoken");
    renderApp("/routes/7/calibration", handler(calibration));

    const transit = within(await screen.findByRole("article", { name: "구간 2" }));
    expect(transit.getByRole("heading", { level: 2 })).toHaveTextContent("GTX-A (GTX) 동탄 → 수서");
    const rows = within(transit.getByRole("table", { name: "예측 오차" })).getAllByRole("row");
    expect(rows).toHaveLength(3); // header + 2
    const weekday = within(rows[1]!);
    expect(weekday.getByText("평일")).toBeInTheDocument();
    expect(weekday.getByText("07:30–08:00")).toBeInTheDocument();
    expect(weekday.getByText("+20초 ± 45초")).toBeInTheDocument();
    expect(weekday.queryByText("신뢰도 낮음")).toBeNull();
    expect(rows[1]).not.toHaveClass("low-sample");

    const saturday = within(rows[2]!);
    expect(saturday.getByText("토요일")).toBeInTheDocument();
    expect(saturday.getByText("23:30–24:00")).toBeInTheDocument();
    expect(saturday.getByText("−10초 ± 30초")).toBeInTheDocument();
    expect(saturday.getByText("신뢰도 낮음")).toBeInTheDocument();
    expect(rows[2]).toHaveClass("low-sample");

    // 합치면 14개라 샘플이 부족한 시간대는 합친 값으로 내려간다.
    expect(transit.getByText(/모든 시간대를 합친 값, 샘플 14개/)).toBeInTheDocument();

    expect(transit.queryByRole("table", { name: "차내 시간" })).toBeNull();
    expect(transit.getByText("아직 기록이 없어 기본값으로 추천합니다")).toBeInTheDocument();
    expect(transit.getByText("20분 ± 3분", { selector: "strong" })).toBeInTheDocument();
  });

  it("falls back to defaults with the empty-state text when nothing is recorded", async () => {
    setToken("devtoken");
    renderApp(
      "/routes/7/calibration",
      handler({
        ...calibration,
        globalWalkingProfile: undefined,
        legs: calibration.legs.map((leg) => ({
          ...leg,
          walkingProfile: undefined,
          predictionRows: [],
          travelTimeRows: [],
        })),
      }),
    );

    const walk = within(await screen.findByRole("article", { name: "구간 1" }));
    expect(walk.getByText("아직 기록이 없어 기본값으로 추천합니다")).toBeInTheDocument();
    expect(walk.getByText("1.20 ± 0.15 m/s", { selector: "strong" })).toBeInTheDocument();
    expect(walk.getByText("(기본값)")).toBeInTheDocument();

    const transit = within(screen.getByRole("article", { name: "구간 2" }));
    expect(transit.getAllByText("아직 기록이 없어 기본값으로 추천합니다")).toHaveLength(2);
    expect(transit.getByText("±0초 ± 1분 30초", { selector: "strong" })).toBeInTheDocument();
    expect(screen.queryByText("신뢰도 낮음")).toBeNull();
  });

  it("shows the API error for another user's route", async () => {
    setToken("devtoken");
    renderApp("/routes/9/calibration", () =>
      json({ title: "Not Found", status: 404, detail: "commute route 9 not found" }, 404),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("commute route 9 not found");
  });
});
