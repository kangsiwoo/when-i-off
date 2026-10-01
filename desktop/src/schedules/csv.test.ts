import { describe, expect, it } from "vitest";
import {
  checkFileSize,
  decodeUtf8,
  parseLocalTime,
  parseScheduleCsv,
  summarize,
  toLongOrNull,
  type ParseResult,
} from "./csv";

const HEADER = "transit_line_id,transit_stop_id,day_type,direction_code,scheduled_time";

function rowsOf(result: ParseResult) {
  if (!result.ok) throw new Error(`expected ok, got ${result.problem.message}`);
  return result.rows;
}

function problemOf(result: ParseResult) {
  if (result.ok) throw new Error("expected a problem");
  return result.problem;
}

describe("parseScheduleCsv — accepted input (mirrors TransitScheduleService.parseRows)", () => {
  it("parses the docs example and skips the header", () => {
    const result = parseScheduleCsv(
      `${HEADER}\n12,45,WEEKDAY,UP,23:30\n12,45,WEEKDAY,DN,23:36\n12,45,SATURDAY,UP,05:30\n`,
    );
    expect(result).toMatchObject({ ok: true, headerSkipped: true, blankLines: 0 });
    expect(rowsOf(result)).toEqual([
      {
        number: 2,
        lineId: "12",
        stopId: "45",
        dayType: "WEEKDAY",
        directionCode: "UP",
        scheduledTime: "23:30",
      },
      {
        number: 3,
        lineId: "12",
        stopId: "45",
        dayType: "WEEKDAY",
        directionCode: "DN",
        scheduledTime: "23:36",
      },
      {
        number: 4,
        lineId: "12",
        stopId: "45",
        dayType: "SATURDAY",
        directionCode: "UP",
        scheduledTime: "05:30",
      },
    ]);
  });

  it("works without a header, with CRLF / CR line breaks and blank lines (row numbers count them)", () => {
    const rows = rowsOf(
      parseScheduleCsv("1,2,WEEKDAY,UP,05:30\r\n\r\n1,2,WEEKDAY,UP,05:40\r1,2,WEEKDAY,UP,05:50"),
    );
    expect(rows.map((r) => r.number)).toEqual([1, 3, 4]);
  });

  it("strips a UTF-8 BOM, trims cells, and upper-cases day_type", () => {
    const rows = rowsOf(
      parseScheduleCsv(`\uFEFF${HEADER}\n 1 ,\t2, sunday_holiday ,DN , 23:30:15 \n`),
    );
    expect(rows).toEqual([
      {
        number: 2,
        lineId: "1",
        stopId: "2",
        dayType: "SUNDAY_HOLIDAY",
        directionCode: "DN",
        scheduledTime: "23:30:15",
      },
    ]);
  });

  it("recognizes the header case-insensitively and ignores the rest of the header line", () => {
    const result = parseScheduleCsv("Transit_Line_ID ,whatever\n1,2,WEEKDAY,UP,05:30");
    expect(result).toMatchObject({ ok: true, headerSkipped: true });
  });

  it("trims like Kotlin: NBSP, ideographic space and U+001F are whitespace", () => {
    const rows = rowsOf(parseScheduleCsv("1,\u00A02\u3000,WEEKDAY,UP,05:30\u001F"));
    expect(rows[0]).toMatchObject({ stopId: "2", scheduledTime: "05:30" });
  });

  it("accepts ids the way Kotlin toLongOrNull does (sign, leading zeros, unicode digits)", () => {
    const rows = rowsOf(
      parseScheduleCsv("+0012,\uFF14\uFF15,WEEKDAY,UP,05:30\n-1,\u0663,WEEKDAY,UP,05:30"),
    );
    expect(rows.map((r) => [r.lineId, r.stopId])).toEqual([
      ["12", "45"],
      ["-1", "3"],
    ]);
  });

  it("keeps the direction code verbatim (case-sensitive, any vocabulary)", () => {
    const rows = rowsOf(parseScheduleCsv("1,2,WEEKDAY,up,05:30\n1,2,WEEKDAY,0,05:30"));
    expect(rows.map((r) => r.directionCode)).toEqual(["up", "0"]);
  });
});

describe("parseScheduleCsv — rejected input (same detail as the server's 400)", () => {
  const cases: [string, string, string][] = [
    [
      "old 4-column file (no direction)",
      `${HEADER}\n12,45,WEEKDAY,23:30`,
      "row 2: expected 5 columns (transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time) but got 4",
    ],
    [
      "trailing comma = 6 columns",
      "12,45,WEEKDAY,UP,23:30,",
      "row 1: expected 5 columns (transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time) but got 6",
    ],
    ["non-numeric line id", "L09,45,WEEKDAY,UP,23:30", "row 1: transit_line_id must be a number"],
    ["decimal stop id", "12,4.5,WEEKDAY,UP,23:30", "row 1: transit_stop_id must be a number"],
    ["empty line id", ",45,WEEKDAY,UP,23:30", "row 1: transit_line_id must be a number"],
    ["sign only", "+,45,WEEKDAY,UP,23:30", "row 1: transit_line_id must be a number"],
    [
      "Long overflow",
      "9223372036854775808,45,WEEKDAY,UP,23:30",
      "row 1: transit_line_id must be a number",
    ],
    [
      "unknown day_type",
      "12,45,HOLIDAY,UP,23:30",
      "row 1: unknown day_type 'HOLIDAY' (WEEKDAY, SATURDAY, SUNDAY_HOLIDAY)",
    ],
    ["blank direction", "12,45,WEEKDAY, ,23:30", "row 1: direction_code must not be blank"],
    [
      "one-digit hour",
      "12,45,WEEKDAY,UP,7:30",
      "row 1: scheduled_time '7:30' must be HH:mm or HH:mm:ss (KST)",
    ],
    [
      "24:00",
      "12,45,WEEKDAY,UP,24:00",
      "row 1: scheduled_time '24:00' must be HH:mm or HH:mm:ss (KST)",
    ],
    [
      "leap second",
      "12,45,WEEKDAY,UP,23:59:60",
      "row 1: scheduled_time '23:59:60' must be HH:mm or HH:mm:ss (KST)",
    ],
    ["empty time", "12,45,WEEKDAY,UP,", "row 1: scheduled_time '' must be HH:mm or HH:mm:ss (KST)"],
    [
      "header not on the first non-blank line is data",
      `1,2,WEEKDAY,UP,05:30\n${HEADER}`,
      "row 2: transit_line_id must be a number",
    ],
    [
      "BOM inside a cell is not whitespace",
      "12,\uFEFF45,WEEKDAY,UP,23:30",
      "row 1: transit_stop_id must be a number",
    ],
    [
      "zero-width space is not whitespace",
      "12,\u200B45,WEEKDAY,UP,23:30",
      "row 1: transit_stop_id must be a number",
    ],
    [
      "NEL is neither a line break nor whitespace",
      "12,45,WEEKDAY,UP,05:30\u0085",
      "row 1: scheduled_time '05:30\u0085' must be HH:mm or HH:mm:ss (KST)",
    ],
    [
      "U+2028 does not split lines",
      "12,45,WEEKDAY,UP,05:30\u202812,45,WEEKDAY,UP,05:31",
      "row 1: expected 5 columns (transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time) but got 9",
    ],
    [
      "semicolon separated",
      "12;45;WEEKDAY;UP;23:30",
      "row 1: expected 5 columns (transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time) but got 1",
    ],
  ];

  it.each(cases)("%s", (_name, csv, message) => {
    expect(problemOf(parseScheduleCsv(csv)).message).toBe(message);
  });

  it("stops at the first bad row and reports its file line number and text", () => {
    const problem = problemOf(
      parseScheduleCsv(`${HEADER}\n\n1,2,WEEKDAY,UP,05:30\n1,2,WEEKDAY,UP,5:40\n1,2,X,UP,05:50`),
    );
    expect(problem).toEqual({
      row: 4,
      message: "row 4: scheduled_time '5:40' must be HH:mm or HH:mm:ss (KST)",
      line: "1,2,WEEKDAY,UP,5:40",
    });
  });

  it("rejects a header-only or blank file", () => {
    expect(problemOf(parseScheduleCsv(`${HEADER}\n`))).toEqual({
      row: null,
      message: "csv has no data row",
    });
    expect(problemOf(parseScheduleCsv(" \n\n"))).toEqual({
      row: null,
      message: "csv has no data row",
    });
  });

  it("rejects an empty file before parsing", () => {
    expect(checkFileSize(0)).toEqual({ row: null, message: "file must not be empty" });
    expect(checkFileSize(1)).toBeNull();
  });
});

describe("parseLocalTime (ISO_LOCAL_TIME)", () => {
  it.each([
    ["23:30", "23:30"],
    ["23:30:00", "23:30"],
    ["00:00", "00:00"],
    ["07:30:05", "07:30:05"],
    ["07:30:05.", "07:30:05"],
    ["07:30:05.5", "07:30:05.500"],
    ["07:30:05.12345", "07:30:05.123450"],
    ["23:59:59.999999999", "23:59:59.999999999"],
  ])("%s → %s", (input, expected) => {
    expect(parseLocalTime(input)).toBe(expected);
  });

  it.each([
    "7:30",
    "07:3",
    "24:00",
    "23:60",
    "07:30.",
    "07:30:05.1234567890",
    "07:30Z",
    "+07:30",
    "07:30:05,1",
    "\uFF10\uFF17:30",
  ])("rejects %s", (input) => expect(parseLocalTime(input)).toBeNull());
});

describe("toLongOrNull", () => {
  it("mirrors Kotlin", () => {
    expect(toLongOrNull("9223372036854775807")).toBe(9223372036854775807n);
    expect(toLongOrNull("-9223372036854775808")).toBe(-9223372036854775808n);
    expect(toLongOrNull("9223372036854775808")).toBeNull();
    expect(toLongOrNull("")).toBeNull();
    expect(toLongOrNull("-")).toBeNull();
    expect(toLongOrNull("1e3")).toBeNull();
    expect(toLongOrNull(" 1")).toBeNull();
  });
});

describe("decodeUtf8", () => {
  it("keeps the BOM (the parser strips it like the server) and replaces broken bytes", () => {
    const text = decodeUtf8(new Uint8Array([0xef, 0xbb, 0xbf, 0x31, 0xff]));
    expect(text).toBe("\uFEFF1\uFFFD");
  });
});

describe("summarize", () => {
  it("counts per line/stop/direction/day_type and lists replacement groups with in-file duplicates", () => {
    const rows = rowsOf(
      parseScheduleCsv(
        [
          HEADER,
          "1,4,WEEKDAY,UP,06:10",
          "1,4,WEEKDAY,UP,05:30",
          "1,4,WEEKDAY,UP,05:30:00", // 같은 시각 → 서버 skipped
          "1,4,WEEKDAY,DN,05:40",
          "1,1,SATURDAY,DN,07:00",
        ].join("\n"),
      ),
    );
    const s = summarize(rows);
    expect(s).toMatchObject({ rows: 5, departures: 4, duplicates: 1 });
    expect(s.byLine).toEqual([{ key: "1", count: 5 }]);
    expect(s.byStop).toEqual([
      { key: "4", count: 4 },
      { key: "1", count: 1 },
    ]);
    expect(s.byDirection).toEqual([
      { key: "UP", count: 3 },
      { key: "DN", count: 2 },
    ]);
    expect(s.byDayType).toEqual([
      { key: "WEEKDAY", count: 4 },
      { key: "SATURDAY", count: 1 },
    ]);
    expect(s.groups).toEqual([
      {
        lineId: "1",
        stopId: "4",
        dayType: "WEEKDAY",
        directionCode: "UP",
        rows: 3,
        departures: 2,
        duplicates: 1,
        first: "05:30",
        last: "06:10",
      },
      {
        lineId: "1",
        stopId: "4",
        dayType: "WEEKDAY",
        directionCode: "DN",
        rows: 1,
        departures: 1,
        duplicates: 0,
        first: "05:40",
        last: "05:40",
      },
      {
        lineId: "1",
        stopId: "1",
        dayType: "SATURDAY",
        directionCode: "DN",
        rows: 1,
        departures: 1,
        duplicates: 0,
        first: "07:00",
        last: "07:00",
      },
    ]);
  });
});
