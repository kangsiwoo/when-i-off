import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import type { CommuteRoute } from "../api/client";
import { setToken } from "../auth/token";
import { detail, route } from "../test/fixtures";
import { json, renderApp } from "../test/render";

beforeEach(() => setToken("devtoken"));

describe("route settings (#68)", () => {
  it("shows the default target and PATCHes only the changed fields", async () => {
    let patched: unknown;
    let current: CommuteRoute = route;
    const { fetch } = renderApp("/routes/7", async (req) => {
      const url = new URL(req.url);
      if (req.method === "PATCH" && url.pathname === "/api/v1/commute-routes/7") {
        patched = await req.json();
        current = {
          ...route,
          defaultTargetArrivalTime: "08:45:00",
          defaultTargetDayTypes: ["WEEKDAY", "SATURDAY"],
        };
        return json(current);
      }
      if (req.method === "GET" && url.pathname === "/api/v1/commute-routes/7")
        return json({ ...detail, route: current });
      if (req.method === "GET" && url.pathname === "/api/v1/commute-routes") return json([current]);
      throw new Error(`unexpected ${req.method} ${req.url}`);
    });

    expect(await screen.findByText("09:00 도착 · 평일")).toBeInTheDocument();
    const form = screen.getByRole("form", { name: "경로 설정" });
    const f = within(form);
    expect(f.getByLabelText("목표 도착 시각 (KST)")).toHaveValue("09:00");
    expect(f.getByLabelText("평일")).toBeChecked();
    expect(f.getByLabelText("토요일")).not.toBeChecked();

    fireEvent.change(f.getByLabelText("목표 도착 시각 (KST)"), { target: { value: "08:45" } });
    fireEvent.click(f.getByLabelText("토요일"));
    fireEvent.click(f.getByRole("button", { name: "저장" }));

    expect(await f.findByRole("status")).toHaveTextContent("저장했습니다.");
    expect(patched).toEqual({
      defaultTargetArrivalTime: "08:45",
      defaultTargetDayTypes: ["WEEKDAY", "SATURDAY"],
    });
    await waitFor(() => expect(screen.getByText("08:45 도착 · 평일, 토요일")).toBeInTheDocument());
    expect(fetch.mock.calls.filter(([r]) => r.method === "PATCH")).toHaveLength(1);
  });

  it("clears the time with the explicit flag and blocks an empty day set", async () => {
    let patched: unknown;
    let current: CommuteRoute = route;
    renderApp("/routes/7", async (req) => {
      if (req.method === "PATCH") {
        patched = await req.json();
        current = { ...route, defaultTargetArrivalTime: null };
        return json(current);
      }
      return json({ ...detail, route: current });
    });

    const form = await screen.findByRole("form", { name: "경로 설정" });
    const f = within(form);
    fireEvent.click(f.getByLabelText("평일"));
    fireEvent.click(f.getByRole("button", { name: "저장" }));
    expect(await f.findByRole("alert")).toHaveTextContent("추천할 날을 하나 이상 고르세요.");
    expect(patched).toBeUndefined();

    fireEvent.click(f.getByLabelText("평일"));
    fireEvent.change(f.getByLabelText("목표 도착 시각 (KST)"), { target: { value: "" } });
    fireEvent.click(f.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(patched).toEqual({ clearDefaultTargetArrivalTime: true }));
    expect(await screen.findByText("없음 (일괄 추천 안 함)")).toBeInTheDocument();
  });

  it("shows the server's validation errors", async () => {
    renderApp("/routes/7", (req) =>
      req.method === "PATCH"
        ? json(
            {
              title: "Bad Request",
              status: 400,
              detail: "validation failed",
              errors: ["name: must not be blank"],
            },
            400,
          )
        : json(detail),
    );
    const form = await screen.findByRole("form", { name: "경로 설정" });
    fireEvent.change(within(form).getByLabelText("이름"), { target: { value: "새 이름" } });
    fireEvent.click(within(form).getByRole("button", { name: "저장" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("name: must not be blank");
  });
});
