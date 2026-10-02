import { describe, expect, it } from "vitest";
import type { CallWindow, ExternalOp } from "../api/client";
import { failureBreakdown, formatMs, formatRate, isHighFailure, quotaLevel } from "./ops";

const window = (calls: number, failures: number, extra: Partial<CallWindow> = {}): CallWindow => ({
  calls,
  failures,
  failureRate: calls === 0 ? undefined : failures / calls,
  outcomes: { success: calls - failures, httpError: 0, apiError: failures, timeout: 0, ioError: 0 },
  ...extra,
});

const op = (today: CallWindow, lastHour: CallWindow): ExternalOp => ({
  op: "tl_drct_info",
  today,
  lastHour,
  quota: { dailyLimit: 1000, used: today.calls, usageRate: today.calls / 1000 },
});

describe("ops rules", () => {
  it("flags a high failure rate in either window, never an idle op", () => {
    expect(isHighFailure(op(window(10, 1), window(2, 0)))).toBe(false);
    expect(isHighFailure(op(window(10, 2), window(0, 0)))).toBe(true); // 20%는 강조
    expect(isHighFailure(op(window(100, 5), window(4, 2)))).toBe(true); // 최근 1시간만 높아도
    expect(isHighFailure(op(window(0, 0), window(0, 0)))).toBe(false);
  });

  it("grades quota usage at 70% and 90%", () => {
    expect(quotaLevel(0.69)).toBe("ok");
    expect(quotaLevel(0.7)).toBe("warning");
    expect(quotaLevel(0.9)).toBe("critical");
    expect(quotaLevel(1.4)).toBe("critical");
  });

  it("formats rates, latency and the failure breakdown", () => {
    expect(formatRate(undefined)).toBe("—");
    expect(formatRate(0)).toBe("0%");
    expect(formatRate(0.0004)).toBe("<0.1%");
    expect(formatRate(0.004)).toBe("0.4%");
    expect(formatRate(0.25)).toBe("25%");
    expect(formatMs(undefined)).toBe("—");
    expect(formatMs(87)).toBe("87ms");
    expect(formatMs(1530)).toBe("1.5s");
    expect(
      failureBreakdown(
        window(5, 2, {
          outcomes: { success: 3, httpError: 0, apiError: 1, timeout: 1, ioError: 0 },
        }),
      ),
    ).toEqual(["결과 코드 오류 1", "타임아웃 1"]);
    expect(failureBreakdown(window(3, 0))).toEqual([]);
  });
});
