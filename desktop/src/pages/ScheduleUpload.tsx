import { useId, useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, type DayType, type SyncCounts } from "../api/client";
import { useImportSchedules } from "../api/queries";
import { dayTypeLabel } from "../format";
import {
  checkFileSize,
  decodeUtf8,
  MAX_UPLOAD_BYTES,
  parseScheduleCsv,
  summarize,
  type Count,
  type CsvProblem,
  type ScheduleRow,
  type ScheduleSummary,
} from "../schedules/csv";
import { lineName, stopName, type KnownTransit } from "../schedules/next";

type Picked =
  | { kind: "none" }
  | { kind: "reading"; file: File }
  | { kind: "invalid"; file: File; problem: CsvProblem }
  | {
      kind: "valid";
      file: File;
      rows: ScheduleRow[];
      summary: ScheduleSummary;
      headerSkipped: boolean;
      blankLines: number;
    };

const formatBytes = (n: number) =>
  n < 1024
    ? `${n} B`
    : n < 1024 * 1024
      ? `${(n / 1024).toFixed(1)} KB`
      : `${(n / 1024 / 1024).toFixed(1)} MB`;

/**
 * 정적 시간표 CSV 업로드 (#70). 파일을 고르면 서버와 같은 규칙(`src/schedules/csv.ts`)으로 먼저 검사하고,
 * 통과하면 미리보기와 교체 범위를 보여 준다. 교체 범위를 확인해야 올릴 수 있다.
 */
export function ScheduleUpload({
  known,
  onParsed,
  onUploaded,
}: {
  known: KnownTransit;
  /** 검사를 통과한 행(다음 출발 후보용). 파일을 바꾸거나 검사에 걸리면 빈 배열. */
  onParsed: (rows: ScheduleRow[]) => void;
  onUploaded: (rows: ScheduleRow[]) => void;
}) {
  const fileId = useId();
  const upload = useImportSchedules();
  const [picked, setPicked] = useState<Picked>({ kind: "none" });
  const [confirmed, setConfirmed] = useState(false);
  const [result, setResult] = useState<SyncCounts | null>(null);

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    upload.reset();
    setResult(null);
    setConfirmed(false);
    onParsed([]);
    if (!file) return setPicked({ kind: "none" });
    setPicked({ kind: "reading", file });
    const sizeProblem = checkFileSize(file.size);
    if (sizeProblem) return setPicked({ kind: "invalid", file, problem: sizeProblem });
    const parsed = parseScheduleCsv(decodeUtf8(await file.arrayBuffer()));
    if (!parsed.ok) return setPicked({ kind: "invalid", file, problem: parsed.problem });
    setPicked({
      kind: "valid",
      file,
      rows: parsed.rows,
      summary: summarize(parsed.rows),
      headerSkipped: parsed.headerSkipped,
      blankLines: parsed.blankLines,
    });
    onParsed(parsed.rows);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (picked.kind !== "valid" || !confirmed) return;
    const rows = picked.rows;
    upload.mutate(picked.file, {
      onSuccess: (counts) => {
        setResult(counts);
        setConfirmed(false);
        onUploaded(rows);
      },
    });
  };

  return (
    <form className="card schedule-upload" onSubmit={submit} aria-label="시간표 CSV 업로드">
      <h2>CSV 업로드</h2>
      <p className="muted small">
        열은 <code>transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time</code>{" "}
        (첫 줄 헤더는 건너뜀). id는 노선·정류장의 <strong>내부 id</strong>, day_type은{" "}
        <code>WEEKDAY|SATURDAY|SUNDAY_HOLIDAY</code>, 시각은 KST <code>HH:mm</code> 또는{" "}
        <code>HH:mm:ss</code>. 방향 코드는 노선의 정류장 순서와 같은 값(GTX-A는 <code>UP</code>=수서
        방면, <code>DN</code>=동탄 방면).
      </p>
      <div className="field">
        <label htmlFor={fileId}>CSV 파일 (UTF-8)</label>
        <input id={fileId} type="file" accept=".csv,text/csv" onChange={(e) => void onFile(e)} />
      </div>

      {picked.kind === "reading" && <p className="muted">파일을 읽는 중…</p>}
      {picked.kind === "invalid" && <CsvProblemView file={picked.file} problem={picked.problem} />}
      {picked.kind === "valid" && (
        <>
          <Preview
            file={picked.file}
            summary={picked.summary}
            headerSkipped={picked.headerSkipped}
            blankLines={picked.blankLines}
            known={known}
          />
          <ReplaceScope summary={picked.summary} known={known} />
          {!result && (
            <div className="upload-actions">
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={confirmed}
                  onChange={(e) => setConfirmed(e.target.checked)}
                />
                위 {picked.summary.groups.length}개 조합의 기존 시간표를 지우고 이 파일로 바꿉니다
              </label>
              <button type="submit" className="primary" disabled={!confirmed || upload.isPending}>
                {upload.isPending ? "올리는 중…" : "업로드"}
              </button>
            </div>
          )}
        </>
      )}
      {upload.error && <ServerError error={upload.error} />}
      {result && <UploadResult result={result} />}
    </form>
  );
}

function CsvProblemView({ file, problem }: { file: File; problem: CsvProblem }) {
  return (
    <div role="alert" className="problem-box">
      <p className="error">
        <strong>{file.name}</strong>은(는) 올릴 수 없습니다. 서버도 같은 이유로 거절합니다(400).
      </p>
      <pre className="detail">{problem.message}</pre>
      {problem.line !== undefined && (
        <p className="small">
          <span className="muted">{problem.row}번째 줄:</span> <code>{problem.line}</code>
        </p>
      )}
      <p className="muted small">
        파일을 고친 뒤 다시 고르세요. 한 행이라도 틀리면 전체가 들어가지 않습니다.
      </p>
    </div>
  );
}

function CountList({
  title,
  counts,
  label,
}: {
  title: string;
  counts: Count[];
  label: (key: string) => string;
}) {
  return (
    <div className="count-list">
      <h3>{title}</h3>
      <ul aria-label={title}>
        {counts.map((c) => (
          <li key={c.key}>
            <span>{label(c.key)}</span>
            <span className="count">{c.count}행</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Preview({
  file,
  summary,
  headerSkipped,
  blankLines,
  known,
}: {
  file: File;
  summary: ScheduleSummary;
  headerSkipped: boolean;
  blankLines: number;
  known: KnownTransit;
}) {
  const notes = [
    headerSkipped ? "헤더 1줄 건너뜀" : "헤더 없음",
    blankLines > 0 ? `빈 줄 ${blankLines}개` : null,
  ]
    .filter(Boolean)
    .join(" · ");
  return (
    <section aria-label="미리보기" className="schedule-preview">
      <p className="preview-head">
        <span className="badge badge-caught">검사 통과</span> <strong>{file.name}</strong>{" "}
        <span className="muted small">
          {formatBytes(file.size)} · {notes}
        </span>
      </p>
      <dl className="facts">
        <dt>데이터 행</dt>
        <dd>{summary.rows}행</dd>
        <dt>넣을 차편</dt>
        <dd>
          {summary.departures}편
          {summary.duplicates > 0 && (
            <span className="muted small">
              {" "}
              (파일 안 중복 시각 {summary.duplicates}행은 한 번만 들어감)
            </span>
          )}
        </dd>
      </dl>
      {file.size > MAX_UPLOAD_BYTES && (
        <p className="warn-note small">
          파일이 {formatBytes(file.size)}로 서버 기본 업로드 한도(1 MB)를 넘습니다. 서버가 413으로
          거절할 수 있으니 노선·정류장별로 나눠 올리세요.
        </p>
      )}
      <div className="count-grid">
        <CountList title="노선별" counts={summary.byLine} label={(k) => lineName(k, known)} />
        <CountList title="정류장별" counts={summary.byStop} label={(k) => stopName(k, known)} />
        <CountList title="방향별" counts={summary.byDirection} label={(k) => k} />
        <CountList
          title="day_type별"
          counts={summary.byDayType}
          label={(k) => `${dayTypeLabel(k as DayType)} (${k})`}
        />
      </div>
    </section>
  );
}

function ReplaceScope({ summary, known }: { summary: ScheduleSummary; known: KnownTransit }) {
  return (
    <section className="replace-scope" aria-label="교체 범위">
      <h3>교체 범위 — 업로드하면 지워지는 시간표</h3>
      <p>
        아래 <strong>{summary.groups.length}개 조합(노선·정류장·day_type·방향)</strong>마다 DB에
        있던 시간표를 <strong>모두 지우고</strong> 이 파일의 차편으로 바꿉니다. 파일에서 빠진 차편은
        사라집니다. 파일에 없는 조합(예: 같은 정류장의 반대 방향, 다른 day_type)은 건드리지
        않습니다.
      </p>
      <div className="table-scroll">
        <table className="scope-table">
          <thead>
            <tr>
              <th>노선</th>
              <th>정류장</th>
              <th>day_type</th>
              <th>방향</th>
              <th>넣을 차편</th>
              <th>첫차 ~ 막차 (KST)</th>
            </tr>
          </thead>
          <tbody>
            {summary.groups.map((g) => (
              <tr key={`${g.lineId}|${g.stopId}|${g.dayType}|${g.directionCode}`}>
                <td>{lineName(g.lineId, known)}</td>
                <td>{stopName(g.stopId, known)}</td>
                <td>{dayTypeLabel(g.dayType)}</td>
                <td>
                  <code>{g.directionCode}</code>
                </td>
                <td>
                  {g.departures}편
                  {g.duplicates > 0 && <span className="muted small"> (중복 {g.duplicates})</span>}
                </td>
                <td>
                  {g.first} ~ {g.last}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** 서버 거절. `detail`을 그대로 보여 준다 (행 번호가 들어 있다). */
function ServerError({ error }: { error: Error }) {
  const status = error instanceof ApiError ? error.status : null;
  const problem = error instanceof ApiError ? error.problem : undefined;
  const title =
    typeof problem === "object" && problem !== null && "title" in problem
      ? String(problem.title)
      : null;
  return (
    <div role="alert" className="problem-box">
      <p className="error">
        서버가 거절했습니다{status ? ` (HTTP ${status}${title ? ` ${title}` : ""})` : ""}. 아무 행도
        들어가지 않았습니다.
      </p>
      <pre className="detail">{error.message}</pre>
    </div>
  );
}

function UploadResult({ result }: { result: SyncCounts }) {
  return (
    <div role="status" className="upload-result">
      <p>
        <span className="badge badge-caught">업로드 완료</span> 아래 "다음 출발"에서 확인하세요.
      </p>
      <dl className="facts">
        <dt>데이터 행 (fetched)</dt>
        <dd>{result.fetched}</dd>
        <dt>새로 생긴 차편 (created)</dt>
        <dd>{result.created}</dd>
        <dt>같은 시각으로 있던 차편 (updated)</dt>
        <dd>{result.updated}</dd>
        <dt>파일 안 중복 (skipped)</dt>
        <dd>{result.skipped}</dd>
      </dl>
      <p className="muted small">
        서버 응답 <code>{JSON.stringify(result)}</code>
      </p>
    </div>
  );
}
