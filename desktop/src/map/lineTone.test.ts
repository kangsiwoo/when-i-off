import { describe, expect, it } from "vitest";
import { lineTone } from "./lineTone";

describe("lineTone", () => {
  it("GTX는 GTX-A 보라, 버스는 광역버스 빨강", () => {
    expect(lineTone({ name: "GTX-A", mode: "GTX" })).toBe("gtx-a");
    expect(lineTone({ name: "M4108", mode: "BUS" })).toBe("bus-red");
  });

  it("아는 지하철 노선은 그 노선색, 모르는 노선과 노선 없음은 잉크", () => {
    expect(lineTone({ name: "수인분당선", mode: "SUBWAY" })).toBe("suin");
    expect(lineTone({ name: "2호선", mode: "SUBWAY" })).toBe("line-2");
    expect(lineTone({ name: "서울 지하철 2호선", mode: "SUBWAY" })).toBe("line-2");
    expect(lineTone({ name: "12호선", mode: "SUBWAY" })).toBe("ink");
    expect(lineTone({ name: "3호선", mode: "SUBWAY" })).toBe("ink");
    expect(lineTone(null)).toBe("ink");
  });
});
