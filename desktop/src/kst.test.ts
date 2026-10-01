import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { formatKstTime, isoToKstInput, kstDate, kstInputToIso } from "./kst";

// 브라우저 시간대가 KST가 아니어도 입력·표시가 KST여야 한다. Node는 실행 중 TZ 변경을 반영한다.
describe.each(["UTC", "America/Los_Angeles", "Asia/Seoul"])("KST conversion with TZ=%s", (tz) => {
  beforeEach(() => {
    vi.stubEnv("TZ", tz);
  });
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("the environment really runs in that zone", () => {
    const offset = new Date("2026-01-15T00:00:00Z").getTimezoneOffset();
    expect(offset).toBe({ UTC: 0, "America/Los_Angeles": 480, "Asia/Seoul": -540 }[tz]);
  });

  it("shows UTC instants as KST wall clock", () => {
    expect(isoToKstInput("2026-09-10T22:58:19Z")).toBe("2026-09-11T07:58:19");
    expect(isoToKstInput("2026-09-10T09:58:19.500Z")).toBe("2026-09-10T18:58:19");
    expect(formatKstTime("2026-09-10T22:58:19Z")).toBe("07:58:19");
    expect(kstDate("2026-09-10T22:58:19Z")).toBe("2026-09-11");
  });

  it("reads datetime-local values as KST", () => {
    expect(kstInputToIso("2026-09-11T07:58:19")).toBe("2026-09-10T22:58:19Z");
    expect(kstInputToIso("2026-09-11T07:58")).toBe("2026-09-10T22:58:00Z");
    expect(kstInputToIso("2026-01-01T08:30:00")).toBe("2025-12-31T23:30:00Z");
  });

  it("round-trips", () => {
    const iso = "2026-03-08T10:30:05Z"; // 미국 서머타임 시작일 — KST에는 영향이 없어야 한다
    expect(kstInputToIso(isoToKstInput(iso))).toBe(iso);
  });

  it("rejects empty and malformed input", () => {
    expect(isoToKstInput(null)).toBe("");
    expect(isoToKstInput("nope")).toBe("");
    expect(kstInputToIso("")).toBeNull();
    expect(kstInputToIso("2026-09-11 07:58")).toBeNull();
    expect(kstInputToIso("2026-02-30T07:58:00")).toBeNull();
    expect(kstInputToIso("2026-09-11T24:00:00")).toBeNull();
  });
});
