import { describe, expect, it } from "vitest";
import { detail } from "../test/fixtures";
import { parseScheduleCsv, type ScheduleRow } from "./csv";
import {
  directionCandidates,
  formatWait,
  draftFromRows,
  emptyDraft,
  knownTransit,
  minutesUntil,
  nextQuery,
  stopCandidates,
  type NextDraft,
} from "./next";

const known = knownTransit([detail, undefined]);

function rows(text: string): ScheduleRow[] {
  const r = parseScheduleCsv(text);
  if (!r.ok) throw new Error(r.problem.message);
  return r.rows;
}

const base: NextDraft = {
  ...emptyDraft(new Date("2026-09-25T14:40:00Z")),
  lineId: 3,
  stopId: "4",
  direction: "UP",
};

describe("nextQuery", () => {
  it("builds the query with the KST time as UTC and a trimmed direction", () => {
    expect(base.at).toBe("2026-09-25T23:40:00");
    expect(nextQuery({ ...base, direction: " DN " })).toEqual({
      query: { lineId: 3, stopId: 4, direction: "DN", at: "2026-09-25T14:40:00Z", limit: 5 },
    });
  });

  it.each<[Partial<NextDraft>, string]>([
    [{ lineId: null }, "노선을 고르세요."],
    [{ stopId: "" }, "정류장 id를 숫자로 넣으세요."],
    [{ stopId: "0" }, "정류장 id를 숫자로 넣으세요."],
    [{ stopId: "4a" }, "정류장 id를 숫자로 넣으세요."],
    [{ direction: "  " }, "방향 코드를 넣으세요 (예: UP, DN)."],
    [{ at: "" }, "기준 시각을 넣으세요."],
    [{ limit: "0" }, "대수는 1~50 사이여야 합니다."],
    [{ limit: "51" }, "대수는 1~50 사이여야 합니다."],
    [{ limit: "2.5" }, "대수는 1~50 사이여야 합니다."],
  ])("%o → %s", (patch, problem) => {
    expect(nextQuery({ ...base, ...patch })).toEqual({ problem });
  });
});

describe("candidates", () => {
  const csv = rows(
    "3,9,WEEKDAY,DN,05:30\n3,4,WEEKDAY,UP,05:30\n3,4,SATURDAY,DN,06:00\n8,7,WEEKDAY,UP,05:00",
  );

  it("lists the CSV's stops for the line first, then my route's stops", () => {
    expect(stopCandidates(3, csv, known)).toEqual([9, 4, 5]);
    expect(stopCandidates(8, csv, known)).toEqual([7]);
    expect(stopCandidates(null, csv, known)).toEqual([]);
  });

  it("lists the CSV's directions for the line and stop", () => {
    expect(directionCandidates(3, "4", csv)).toEqual(["UP", "DN"]);
    expect(directionCandidates(3, "5", csv)).toEqual([]);
  });

  it("prefills from the first uploaded group, keeping time and limit", () => {
    const d = draftFromRows({ ...emptyDraft(), at: "2026-09-25T08:00:00", limit: "3" }, csv, known);
    expect(d).toMatchObject({
      lineId: 3,
      stopId: "9",
      direction: "DN",
      at: "2026-09-25T08:00:00",
      limit: "3",
    });
    expect(d.line?.name).toBe("GTX-A");
  });
});

it("minutesUntil floors to minutes", () => {
  expect(minutesUntil("2026-09-25T14:40:00Z", "2026-09-25T14:50:59Z")).toBe(10);
  expect(minutesUntil("2026-09-25T14:40:00Z", "2026-09-25T14:40:00Z")).toBe(0);
});

it("formatWait", () => {
  expect(formatWait(9)).toBe("9분 뒤");
  expect(formatWait(360)).toBe("6시간 뒤");
  expect(formatWait(375)).toBe("6시간 15분 뒤");
});
