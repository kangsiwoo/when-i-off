/**
 * 정적 시간표 CSV 검사와 미리보기 (#70, 화면 없는 순수 로직).
 *
 * 검사는 서버 파서를 **그대로** 옮긴 것이다. 서버를 바꾸면 여기도 같이 바꾼다.
 * - backend/src/main/kotlin/com/kangsiwoo/whenioff/admin/api/ScheduleAdminController.kt
 *   `import`: 빈 파일 → `file must not be empty`, 본문은 `bytes.decodeToString()`(UTF-8, 깨진 바이트는 U+FFFD, BOM 유지)
 * - backend/src/main/kotlin/com/kangsiwoo/whenioff/transit/application/TransitScheduleService.kt
 *   `importCsv`(데이터 행 없음), `parseRows`(줄 나눔·BOM·빈 줄·헤더), `parseRow`(열 수·id·day_type·방향·시각),
 *   교체 단위 `Key`(노선, 정류장, day_type, 방향)와 파일 안 중복 시각(`skipped`)
 * - docs/API.md "정적 시간표"
 *
 * Kotlin 표준 함수의 동작도 맞춘다: `trim()`은 `Char.isWhitespace`(JS `trim()`과 달리 U+FEFF는 남기고
 * U+00A0 등 공백 문자는 지운다), `toLongOrNull()`은 `+`/`-` 부호와 유니코드 숫자(Nd)를 받고 Long 범위를 넘으면
 * null, `LocalTime.parse`는 `ISO_LOCAL_TIME`(HH:mm[:ss[.0~9자리]], 24:00·윤초 없음).
 * 메시지는 서버 `detail`과 같은 문자열이라 화면이 같은 말을 보여 준다.
 *
 * 서버만 알 수 있는 것(없는 노선·정류장 id → `row n: unknown transit_line_id …`)은 검사하지 않는다.
 */
import type { DayType } from "../api/client";

export const COLUMN_NAMES =
  "transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time";
const COLUMNS = 5;
const FIRST_COLUMN = "transit_line_id";
/** `DayType.entries` 순서. 오류 메시지의 목록도 이 순서다. */
export const DAY_TYPES: readonly DayType[] = ["WEEKDAY", "SATURDAY", "SUNDAY_HOLIDAY"];

/** Spring 기본 `spring.servlet.multipart.max-file-size`(1MB). 넘으면 서버가 413을 준다(파서 규칙은 아님). */
export const MAX_UPLOAD_BYTES = 1024 * 1024;

export interface ScheduleRow {
  /** 파일의 줄 번호(1부터, 빈 줄·헤더 포함). 서버 메시지의 `row n`과 같다. */
  number: number;
  /** 정수 id를 10진 문자열로(앞 0·부호 정리). Long 전체 범위라 number로 바꾸지 않는다. */
  lineId: string;
  stopId: string;
  dayType: DayType;
  directionCode: string;
  /** `LocalTime.toString()`과 같은 표기 (`23:30`, `05:30:15`, `05:30:15.500`). 같은 시각이면 같은 문자열이다. */
  scheduledTime: string;
}

export interface CsvProblem {
  /** 문제의 줄 번호. 파일 전체의 문제(빈 파일, 데이터 행 없음)면 null. */
  row: number | null;
  /** 서버 `detail`과 같은 문자열. */
  message: string;
  /** 문제의 줄 원문 (있으면). */
  line?: string;
}

export type ParseResult =
  | { ok: true; rows: ScheduleRow[]; headerSkipped: boolean; blankLines: number }
  | { ok: false; problem: CsvProblem };

/**
 * `ByteArray.decodeToString()`과 같게 읽는다. 깨진 바이트는 U+FFFD로 바꾸고(예외 없음), 맨 앞 BOM은
 * **지우지 않는다**(서버는 줄마다 BOM을 지운다). `Blob.text()`는 BOM을 지우므로 쓰지 않는다.
 */
export function decodeUtf8(bytes: ArrayBuffer | Uint8Array): string {
  return new TextDecoder("utf-8", { ignoreBOM: true }).decode(bytes);
}

// Kotlin `Char.isWhitespace()` (JVM: Character.isWhitespace || Character.isSpaceChar).
const WS = "[\\t\\n\\u000B\\f\\r\\u001C-\\u001F\\p{Zs}\\p{Zl}\\p{Zp}]";
const TRIM = new RegExp(`^${WS}+|${WS}+$`, "gu");
const ktTrim = (s: string) => s.replace(TRIM, "");

const ND = /^\p{Nd}$/u;
const isNd = (code: number) => ND.test(String.fromCharCode(code));

/** `Character.digit(ch, 10)`: 유니코드 Nd 문자의 값. Nd는 0~9가 연속으로 놓이므로 그 줄의 시작에서 센다. */
function digitOf(code: number): number {
  if (!isNd(code)) return -1;
  let start = code;
  while (isNd(start - 1)) start--;
  return (code - start) % 10;
}

const LONG_MIN = -(2n ** 63n);
const LONG_MAX = 2n ** 63n - 1n;

/** Kotlin `String.toLongOrNull()`. */
export function toLongOrNull(s: string): bigint | null {
  if (s.length === 0) return null;
  let i = 0;
  let negative = false;
  if (s.charCodeAt(0) < 0x30) {
    if (s.length === 1) return null;
    if (s[0] === "-") negative = true;
    else if (s[0] !== "+") return null;
    i = 1;
  }
  let value = 0n;
  for (; i < s.length; i++) {
    const d = digitOf(s.charCodeAt(i));
    if (d < 0) return null;
    value = value * 10n + BigInt(d);
  }
  if (negative) value = -value;
  return value < LONG_MIN || value > LONG_MAX ? null : value;
}

const ISO_LOCAL_TIME = /^([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{0,9}))?)?$/;

/** `LocalTime.parse`(ISO_LOCAL_TIME, strict) → `LocalTime.toString()` 표기. 틀리면 null. */
export function parseLocalTime(s: string): string | null {
  const m = ISO_LOCAL_TIME.exec(s);
  if (!m) return null;
  const [h, mi, sec] = [Number(m[1]), Number(m[2]), Number(m[3] ?? 0)];
  if (h > 23 || mi > 59 || sec > 59) return null;
  const nanos = Number((m[4] ?? "").padEnd(9, "0") || 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  let out = `${pad(h)}:${pad(mi)}`;
  if (sec > 0 || nanos > 0) out += `:${pad(sec)}`;
  if (nanos > 0) {
    const nine = String(nanos).padStart(9, "0");
    out += `.${nanos % 1_000_000 === 0 ? nine.slice(0, 3) : nanos % 1000 === 0 ? nine.slice(0, 6) : nine}`;
  }
  return out;
}

/** Java `String.equalsIgnoreCase`: 글자마다 대문자끼리, 아니면 그 소문자끼리 비교한다. */
function equalsIgnoreCase(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  const upper = (c: string) => (c.toUpperCase().length === 1 ? c.toUpperCase() : c);
  const lower = (c: string) => (c.toLowerCase().length === 1 ? c.toLowerCase() : c);
  for (let i = 0; i < a.length; i++) {
    const [x, y] = [a[i]!, b[i]!];
    if (x === y) continue;
    const [ux, uy] = [upper(x), upper(y)];
    if (ux !== uy && lower(ux) !== lower(uy)) return false;
  }
  return true;
}

/** `String.substringBefore(',')`: 쉼표가 없으면 전체. */
const substringBeforeComma = (s: string) => {
  const i = s.indexOf(",");
  return i < 0 ? s : s.slice(0, i);
};

class RowError extends Error {
  readonly row: number;

  constructor(row: number, message: string) {
    super(message);
    this.row = row;
  }
}

function parseRow(number: number, line: string): ScheduleRow {
  const cells = line.split(",").map(ktTrim);
  if (cells.length !== COLUMNS) {
    throw new RowError(
      number,
      `row ${number}: expected ${COLUMNS} columns (${COLUMN_NAMES}) but got ${cells.length}`,
    );
  }
  const [lineCell, stopCell, dayCell, directionCode, timeCell] = cells as [
    string,
    string,
    string,
    string,
    string,
  ];
  const lineId = toLongOrNull(lineCell);
  if (lineId === null)
    throw new RowError(number, `row ${number}: transit_line_id must be a number`);
  const stopId = toLongOrNull(stopCell);
  if (stopId === null)
    throw new RowError(number, `row ${number}: transit_stop_id must be a number`);
  const dayType = DAY_TYPES.find((d) => d === dayCell.toUpperCase());
  if (!dayType) {
    throw new RowError(
      number,
      `row ${number}: unknown day_type '${dayCell}' (${DAY_TYPES.join(", ")})`,
    );
  }
  // 방향 코드는 사업자가 주는 값 그대로(transit_line_stops와 같은 어휘). 서버도 비어 있는지만 본다.
  if (directionCode === "") {
    throw new RowError(number, `row ${number}: direction_code must not be blank`);
  }
  const scheduledTime = parseLocalTime(timeCell);
  if (scheduledTime === null) {
    throw new RowError(
      number,
      `row ${number}: scheduled_time '${timeCell}' must be HH:mm or HH:mm:ss (KST)`,
    );
  }
  return {
    number,
    lineId: lineId.toString(),
    stopId: stopId.toString(),
    dayType,
    directionCode,
    scheduledTime,
  };
}

/** 파일 바이트 수 검사 (`ScheduleAdminController`의 `file.isEmpty`). */
export function checkFileSize(size: number): CsvProblem | null {
  return size === 0 ? { row: null, message: "file must not be empty" } : null;
}

/** 서버 `parseRows` + `importCsv`의 "데이터 행 없음"까지. 첫 문제에서 멈춘다(서버도 첫 행 오류로 400). */
export function parseScheduleCsv(content: string): ParseResult {
  const lines = content.split(/\r\n|\n|\r/);
  const rows: ScheduleRow[] = [];
  let atFirstLine = true;
  let headerSkipped = false;
  let blankLines = 0;
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index]!;
    // 엑셀에서 내보낸 CSV는 BOM으로 시작한다. 서버는 trim 뒤 맨 앞 BOM을 지운다(줄마다).
    const line = ktTrim(raw).replace(/^\uFEFF+/, "");
    if (line === "") {
      // 마지막 줄바꿈 뒤의 빈 조각은 줄로 세지 않는다.
      if (index < lines.length - 1) blankLines++;
      continue;
    }
    const isHeader =
      atFirstLine && equalsIgnoreCase(ktTrim(substringBeforeComma(line)), FIRST_COLUMN);
    atFirstLine = false;
    // 헤더는 첫 줄이면서 첫 칸이 컬럼명일 때만 건너뛴다(나머지 칸은 보지 않는다).
    if (isHeader) {
      headerSkipped = true;
      continue;
    }
    try {
      rows.push(parseRow(index + 1, line));
    } catch (e) {
      if (e instanceof RowError)
        return { ok: false, problem: { row: e.row, message: e.message, line: raw } };
      throw e;
    }
  }
  if (rows.length === 0)
    return { ok: false, problem: { row: null, message: "csv has no data row" } };
  return { ok: true, rows, headerSkipped, blankLines };
}

export interface Count {
  key: string;
  count: number;
}

/** 서버가 지우고 다시 넣는 단위 하나: (노선, 정류장, day_type, 방향). */
export interface ReplaceGroup {
  lineId: string;
  stopId: string;
  dayType: DayType;
  directionCode: string;
  /** 이 조합의 파일 행 수. */
  rows: number;
  /** 서로 다른 시각 수 = 넣을 차편 수. */
  departures: number;
  /** 파일 안 중복 시각(서버 `skipped`). */
  duplicates: number;
  first: string;
  last: string;
}

export interface ScheduleSummary {
  /** 데이터 행 수 = 서버 `fetched`. */
  rows: number;
  /** 넣을 차편 수 = 서버 `created + updated`. */
  departures: number;
  /** 파일 안 중복 행 = 서버 `skipped`. */
  duplicates: number;
  byLine: Count[];
  byStop: Count[];
  byDirection: Count[];
  byDayType: Count[];
  groups: ReplaceGroup[];
}

function countBy(rows: ScheduleRow[], key: (r: ScheduleRow) => string): Count[] {
  const counts = new Map<string, number>();
  for (const r of rows) counts.set(key(r), (counts.get(key(r)) ?? 0) + 1);
  return [...counts].map(([k, count]) => ({ key: k, count }));
}

/** 시각 정렬용 (`HH:mm[:ss[.f]]` 사전순 = 시각순이 되도록 초를 채운다). */
const sortable = (t: string) => (t.length === 5 ? `${t}:00` : t);

/** 미리보기 수치. 순서는 파일에 처음 나온 순서(서버 `groupBy`와 같다). */
export function summarize(rows: ScheduleRow[]): ScheduleSummary {
  const groups = new Map<string, { group: ReplaceGroup; times: Set<string> }>();
  for (const r of rows) {
    const k = JSON.stringify([r.lineId, r.stopId, r.dayType, r.directionCode]);
    let g = groups.get(k);
    if (!g) {
      g = {
        group: {
          lineId: r.lineId,
          stopId: r.stopId,
          dayType: r.dayType,
          directionCode: r.directionCode,
          rows: 0,
          departures: 0,
          duplicates: 0,
          first: r.scheduledTime,
          last: r.scheduledTime,
        },
        times: new Set(),
      };
      groups.set(k, g);
    }
    g.group.rows++;
    if (g.times.has(r.scheduledTime)) g.group.duplicates++;
    else g.times.add(r.scheduledTime);
    if (sortable(r.scheduledTime) < sortable(g.group.first)) g.group.first = r.scheduledTime;
    if (sortable(r.scheduledTime) > sortable(g.group.last)) g.group.last = r.scheduledTime;
  }
  const list = [...groups.values()].map(({ group, times }) => ({
    ...group,
    departures: times.size,
  }));
  const byDayType = countBy(rows, (r) => r.dayType);
  byDayType.sort(
    (a, b) => DAY_TYPES.indexOf(a.key as DayType) - DAY_TYPES.indexOf(b.key as DayType),
  );
  return {
    rows: rows.length,
    departures: list.reduce((n, g) => n + g.departures, 0),
    duplicates: list.reduce((n, g) => n + g.duplicates, 0),
    byLine: countBy(rows, (r) => r.lineId),
    byStop: countBy(rows, (r) => r.stopId),
    byDirection: countBy(rows, (r) => r.directionCode),
    byDayType,
    groups: list,
  };
}
