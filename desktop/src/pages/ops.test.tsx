import { act, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { CallWindow, ExternalOp, OpsStatus } from "../api/client";
import { setToken } from "../auth/token";
import { BASE, json, renderApp } from "../test/render";

const zero = { success: 0, httpError: 0, apiError: 0, timeout: 0, ioError: 0 };

const idle: CallWindow = { calls: 0, failures: 0, outcomes: zero };

function op(name: string, today: CallWindow, lastHour: CallWindow, dailyLimit = 1000): ExternalOp {
  return {
    op: name,
    today,
    lastHour,
    quota: { dailyLimit, used: today.calls, usageRate: today.calls / dailyLimit },
  };
}

const failingArrivals: CallWindow = {
  calls: 4,
  failures: 4,
  failureRate: 1,
  p50Ms: 120,
  p95Ms: 240,
  outcomes: { ...zero, apiError: 4 },
};

const healthySignals: CallWindow = {
  calls: 800,
  failures: 8,
  failureRate: 0.01,
  p50Ms: 310,
  p95Ms: 1530,
  outcomes: { ...zero, success: 792, timeout: 8 },
};

const status: OpsStatus = {
  generatedAt: "2026-10-02T00:30:00Z",
  todayStart: "2026-10-01T15:00:00Z",
  collectingSince: "2026-10-01T23:00:00Z",
  sources: [
    {
      source: "tago",
      configured: true,
      ops: [
        op("getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList", failingArrivals, failingArrivals),
        op("getRouteNoList", idle, idle),
      ],
    },
    {
      source: "klid",
      configured: true,
      ops: [
        op("tl_drct_info", healthySignals, healthySignals, 1000),
        op("crsrd_map_info", idle, idle),
      ],
    },
  ],
  polling: {
    enabled: true,
    intervalMs: 60000,
    windows: ["06:30-09:30", "17:30-20:30"],
    withinWindow: true,
    lastRun: {
      at: "2026-10-02T00:29:00Z",
      result: "SUCCESS",
      legsPredicted: 0,
      predictions: 0,
      signalStatesInserted: 12,
      failedCodes: ["31240"],
    },
  },
  retention: {
    enabled: true,
    dryRun: true,
    cron: "0 30 4 * * *",
    nextRunAt: "2026-10-02T19:30:00Z",
    lastRun: {
      at: "2026-10-01T19:30:00Z",
      result: "SUCCESS",
      dryRun: true,
      rows: [
        { table: "gps_traces", rows: 1234 },
        { table: "traffic_signal_states", rows: 0 },
      ],
    },
  },
};

const PATH = `${BASE}/api/v1/admin/ops/external-apis`;

function handler(body: OpsStatus) {
  return (req: Request) => (req.url === PATH ? json(body) : json([])); // 경로 목록 등 나머지는 비워 둔다
}

afterEach(() => {
  vi.useRealTimers();
});

describe("ops page", () => {
  it("is in the nav and loads the ops endpoint with the token", async () => {
    setToken("devtoken");
    const { fetch } = renderApp("/", handler(status));

    fireEvent.click(await screen.findByRole("link", { name: "운영" }));

    expect(await screen.findByRole("heading", { level: 1, name: "운영" })).toBeInTheDocument();
    const call = fetch.mock.calls.map((c) => c[0]).find((r) => r.url === PATH);
    expect(call?.headers.get("X-Api-Token")).toBe("devtoken");
  });

  it("renders per-op tables, highlights a high failure rate and draws quota meters", async () => {
    setToken("devtoken");
    renderApp("/ops", handler(status));

    const tago = within(await screen.findByRole("table", { name: "TAGO (버스) 호출" }));
    const rows = tago.getAllByRole("row").slice(2); // 머리글 두 줄
    expect(rows).toHaveLength(2);

    const failing = rows[0]!;
    expect(failing).toHaveClass("ops-failing");
    const f = within(failing);
    expect(f.getByText("실패율 높음")).toBeInTheDocument();
    expect(f.getAllByText("100%")).toHaveLength(2); // 오늘, 최근 1시간
    expect(f.getAllByText("결과 코드 오류 4")).toHaveLength(2);
    expect(f.getAllByText("120ms / 240ms")).toHaveLength(2);
    const meter = f.getByRole("meter", { name: /일 한도 사용률/ });
    expect(meter).toHaveAttribute("aria-valuenow", "4");
    expect(meter).toHaveAttribute("aria-valuemax", "1000");
    expect(meter).toHaveAttribute("aria-valuetext", "0.4% (4 / 1,000)");

    const idleRow = rows[1]!;
    expect(idleRow).not.toHaveClass("ops-failing");
    expect(within(idleRow).getAllByText("—")).toHaveLength(2); // 실패율 두 칸 (지연은 "— / —")

    // 실패율 1%는 강조하지 않지만, 한도 80%는 경고 표시(아이콘 + 글자)
    const klid = within(screen.getByRole("table", { name: "KLID (신호등) 호출" }));
    const signals = klid.getAllByRole("row")[2]!;
    expect(signals).not.toHaveClass("ops-failing");
    const s = within(signals);
    expect(s.getAllByText("1.0%")).toHaveLength(2);
    expect(s.getAllByText("타임아웃 8")).toHaveLength(2);
    expect(s.getAllByText("310ms / 1.5s")).toHaveLength(2);
    expect(s.getByRole("meter")).toHaveClass("meter-warning");
    expect(s.getByText("한도 70%+")).toBeInTheDocument();

    expect(screen.queryByText(/아직 기록된 외부 API 호출이 없습니다/)).toBeNull();
  });

  it("shows polling and retention status", async () => {
    setToken("devtoken");
    renderApp("/ops", handler(status));

    const polling = within(await screen.findByRole("article", { name: "폴링" }));
    expect(polling.getByText("켜짐")).toBeInTheDocument();
    expect(polling.getByText(/06:30-09:30, 17:30-20:30 · 지금은 창 안/)).toBeInTheDocument();
    expect(polling.getByText("성공")).toBeInTheDocument();
    expect(polling.getByText("일부 실패: 31240")).toBeInTheDocument();
    expect(polling.getByText(/신호 12건/)).toBeInTheDocument();

    const retention = within(screen.getByRole("article", { name: "보관 정리" }));
    expect(retention.getByText("dry-run")).toBeInTheDocument();
    expect(retention.getByText("0 30 4 * * *")).toBeInTheDocument();
    expect(retention.getByText("성공 (dry-run)")).toBeInTheDocument();
    expect(retention.getByText("지울 행")).toBeInTheDocument();
    expect(retention.getByText("GPS 1,234 · 신호 상태 0")).toBeInTheDocument();
  });

  it("shows an empty state when nothing has been called since the restart", async () => {
    setToken("devtoken");
    renderApp(
      "/ops",
      handler({
        ...status,
        sources: status.sources.map((s) => ({
          ...s,
          configured: s.source === "klid",
          ops: s.ops.map((o) => op(o.op, idle, idle)),
        })),
        polling: { ...status.polling, enabled: false, withinWindow: false, lastRun: undefined },
        retention: {
          ...status.retention,
          enabled: false,
          nextRunAt: undefined,
          lastRun: undefined,
        },
      }),
    );

    expect(await screen.findByText(/아직 기록된 외부 API 호출이 없습니다/)).toBeInTheDocument();
    expect(screen.getByText(/서비스 키 미설정/)).toBeInTheDocument();
    expect(screen.getAllByText("재시작 뒤 아직 없음")).toHaveLength(2);
    expect(screen.getAllByText("꺼짐")).toHaveLength(2);
    expect(document.querySelectorAll("tr.ops-failing")).toHaveLength(0);
    expect(screen.queryByText("실패율 높음")).toBeNull();
  });

  it("refreshes every 30 seconds", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setToken("devtoken");
    const { fetch } = renderApp("/ops", handler(status));
    await screen.findByRole("heading", { level: 1, name: "운영" });
    const calls = () => fetch.mock.calls.filter((c) => c[0].url === PATH).length;
    expect(calls()).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(calls()).toBe(2);
  });
});
