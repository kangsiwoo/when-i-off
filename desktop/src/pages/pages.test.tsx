import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { getToken, setToken } from "../auth/token";
import { detail, route } from "../test/fixtures";
import { BASE, json, renderApp } from "../test/render";

describe("login", () => {
  it("redirects to /login without a token, then stores the token and shows the route list", async () => {
    const { fetch, router } = renderApp("/", (req) => {
      expect(req.headers.get("X-Api-Token")).toBe("devtoken");
      return json([route]);
    });

    expect(await screen.findByLabelText("API 토큰")).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("API 토큰"), { target: { value: " devtoken " } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("link", { name: "집 → 회사" })).toBeInTheDocument();
    expect(getToken()).toBe("devtoken");
    expect(router.state.location.pathname).toBe("/");
  });

  it("sends the user back to /login when the API answers 401", async () => {
    setToken("stale");
    const { router } = renderApp("/", () =>
      json(
        { title: "Unauthorized", status: 401, detail: "missing or invalid X-Api-Token header" },
        401,
      ),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("토큰이 없거나 맞지 않습니다");
    expect(router.state.location.pathname).toBe("/login");
    expect(getToken()).toBeNull();
  });
});

describe("route list", () => {
  it("renders routes from GET /commute-routes with links to the detail", async () => {
    setToken("devtoken");
    const { fetch } = renderApp("/", () =>
      json([route, { ...route, id: 8, name: "회사 → 집", direction: "TO_HOME", isActive: false }]),
    );

    const rows = await screen.findAllByRole("row");
    expect(rows).toHaveLength(3); // header + 2
    expect(within(rows[1]!).getByRole("link", { name: "집 → 회사" })).toHaveAttribute(
      "href",
      "/routes/7",
    );
    expect(within(rows[1]!).getByText("출근")).toBeInTheDocument();
    expect(within(rows[2]!).getByText("퇴근")).toBeInTheDocument();
    expect(within(rows[2]!).getByText("꺼짐")).toBeInTheDocument();
    expect(fetch.mock.calls[0]![0].url).toBe(`${BASE}/api/v1/commute-routes`);
  });

  it("shows an empty state", async () => {
    setToken("devtoken");
    renderApp("/", () => json([]));
    expect(await screen.findByText("등록된 경로가 없습니다.")).toBeInTheDocument();
  });
});

describe("route detail", () => {
  it("renders legs in seq order with line/stops for TRANSIT and distance for WALK", async () => {
    setToken("devtoken");
    const { fetch } = renderApp("/routes/7", () => json(detail));

    expect(await screen.findByRole("heading", { name: "집 → 회사" })).toBeInTheDocument();
    expect(fetch.mock.calls[0]![0].url).toBe(`${BASE}/api/v1/commute-routes/7`);

    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);

    const walk = within(rows[0]!);
    expect(walk.getByText("1")).toBeInTheDocument();
    expect(walk.getByText("도보")).toBeInTheDocument();
    expect(walk.getByText("650 m")).toBeInTheDocument();
    expect(walk.getByText("8분")).toBeInTheDocument();
    expect(walk.getByText("1곳")).toBeInTheDocument();

    const transit = within(rows[1]!);
    expect(transit.getByText("2")).toBeInTheDocument();
    expect(transit.getByText("대중교통")).toBeInTheDocument();
    expect(transit.getByText("GTX-A (GTX)")).toBeInTheDocument();
    expect(transit.getByText("동탄 → 수서")).toBeInTheDocument();
    expect(transit.getByText("20분")).toBeInTheDocument();
  });

  it("shows the problem detail when the route does not exist", async () => {
    setToken("devtoken");
    renderApp("/routes/99", () =>
      json({ title: "Not Found", status: 404, detail: "commute route 99 not found" }, 404),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("commute route 99 not found");
  });
});

it("navigates from the list to the detail", async () => {
  setToken("devtoken");
  renderApp("/", (req) => (req.url.endsWith("/commute-routes") ? json([route]) : json(detail)));
  fireEvent.click(await screen.findByRole("link", { name: "집 → 회사" }));
  await waitFor(() =>
    expect(screen.getByRole("heading", { name: "집 → 회사" })).toBeInTheDocument(),
  );
  expect(screen.getByText("동탄 → 수서")).toBeInTheDocument();
});
