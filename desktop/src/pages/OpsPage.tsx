import type {
  CallWindow,
  ExternalOp,
  ExternalSourceOps,
  PollingStatus,
  RetentionStatus,
} from "../api/client";
import { OPS_REFRESH_MS, useOpsStatus } from "../api/queries";
import { formatDuration } from "../format";
import {
  failureBreakdown,
  formatCount,
  formatKstSeconds,
  formatMs,
  formatRate,
  hasNoCalls,
  HIGH_FAILURE_RATE,
  isHighFailure,
  quotaLevel,
  QUOTA_CRITICAL,
  QUOTA_WARNING,
  sourceLabel,
  type Level,
} from "../ops/ops";
import { ErrorMessage, Loading } from "./Status";

/**
 * 운영 (#76): 외부 API(TAGO/KLID) 호출 수·실패율·지연과 일 한도 사용률, 폴링·보관 배치 상태.
 * `GET /admin/ops/external-apis` 하나로 그리고 30초마다 다시 받는다. 집계는 서버 메모리에만 있어 재시작하면 비어 시작한다.
 */
export function OpsPage() {
  const query = useOpsStatus();

  if (query.isPending) return <Loading />;
  // 새로 고침이 실패해도 받아 둔 값이 있으면 그것을 보여 주고 위에 알린다.
  const status = query.data;
  if (status === undefined) return <ErrorMessage error={query.error ?? new Error("no data")} />;

  return (
    <>
      <h1>운영</h1>
      <p className="muted small">
        {OPS_REFRESH_MS / 1000}초마다 새로 고침 · 갱신 {formatKstSeconds(status.generatedAt)} · 집계
        시작 {formatKstSeconds(status.collectingSince)} (서버를 다시 시작하면 비워집니다)
        {query.isFetching && " · 받는 중…"}
      </p>
      {query.error && (
        <p role="alert" className="error">
          새로 고침에 실패해 이전 값을 보여 줍니다: {query.error.message}
        </p>
      )}

      <section aria-label="배치" className="ops-batches">
        <PollingCard polling={status.polling} />
        <RetentionCard retention={status.retention} />
      </section>

      <h2>외부 API</h2>
      <p className="muted small">
        HTTP 시도 단위(재시도 포함)입니다. 오늘은 KST 자정부터, 최근 1시간은 분 단위 60칸입니다.
        실패율이 {formatRate(HIGH_FAILURE_RATE)} 이상인 행은 강조합니다. 한도는 오퍼레이션마다의 일
        한도(설정값)입니다.
      </p>
      {hasNoCalls(status) && (
        <p className="ops-empty">
          아직 기록된 외부 API 호출이 없습니다. 폴링이 꺼져 있거나 창 밖이면 관리 동기화를 실행했을
          때만 집계됩니다.
        </p>
      )}
      {status.sources.map((source) => (
        <SourceTable key={source.source} source={source} />
      ))}
    </>
  );
}

/** 상태 표시: 아이콘 + 글자. 색만으로 뜻을 싣지 않는다. */
function StatusMark({ level, label }: { level: Level; label: string }) {
  return (
    <span className={`status-mark status-${level}`}>
      <StatusIcon level={level} />
      {label}
    </span>
  );
}

function StatusIcon({ level }: { level: Level }) {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" focusable="false">
      {level === "ok" && (
        <>
          <circle cx="8" cy="8" r="7" fill="currentColor" />
          <path d="M4.5 8.2 7 10.6l4.6-5" stroke="#fff" strokeWidth="1.8" fill="none" />
        </>
      )}
      {level === "warning" && (
        <>
          <path d="M8 1.2 15.2 14H.8Z" fill="currentColor" />
          <path d="M8 5.6v4.2M8 11.4v1.2" stroke="#000" strokeWidth="1.6" />
        </>
      )}
      {level === "critical" && (
        <>
          <circle cx="8" cy="8" r="7" fill="currentColor" />
          <path d="M8 4v5M8 10.8v1.4" stroke="#fff" strokeWidth="1.8" />
        </>
      )}
    </svg>
  );
}

function OnOff({ on }: { on: boolean }) {
  return <span className={`badge ${on ? "badge-on" : "badge-off"}`}>{on ? "켜짐" : "꺼짐"}</span>;
}

function PollingCard({ polling }: { polling: PollingStatus }) {
  const run = polling.lastRun;
  return (
    <article className="card ops-card" aria-label="폴링">
      <h2>
        폴링 <OnOff on={polling.enabled} />
      </h2>
      <dl className="facts">
        <dt>창 (KST)</dt>
        <dd>
          {polling.windows.join(", ") || "항상"} · 지금은 {polling.withinWindow ? "창 안" : "창 밖"}
        </dd>
        <dt>간격</dt>
        <dd>{formatDuration(Math.round(polling.intervalMs / 1000))}</dd>
        <dt>마지막 실행</dt>
        <dd>
          {run ? (
            <>
              {formatKstSeconds(run.at)}{" "}
              {run.result === "SUCCESS" ? (
                <StatusMark level="ok" label="성공" />
              ) : (
                <StatusMark level="critical" label="실패" />
              )}
            </>
          ) : (
            <span className="muted">재시작 뒤 아직 없음</span>
          )}
        </dd>
        {run?.error && (
          <>
            <dt>오류</dt>
            <dd className="ops-error-text">{run.error}</dd>
          </>
        )}
        {run && run.result === "SUCCESS" && (
          <>
            <dt>결과</dt>
            <dd>
              구간 {run.legsPredicted}개 · 예측 {run.predictions}건 · 신호{" "}
              {run.signalStatesInserted}건
              {run.failedCodes.length > 0 && (
                <>
                  {" "}
                  <StatusMark level="warning" label={`일부 실패: ${run.failedCodes.join(", ")}`} />
                </>
              )}
            </dd>
          </>
        )}
      </dl>
    </article>
  );
}

const TABLE_LABEL: Record<string, string> = {
  gps_traces: "GPS",
  transit_arrival_observations: "도착 관측",
  traffic_signal_states: "신호 상태",
};

function RetentionCard({ retention }: { retention: RetentionStatus }) {
  const run = retention.lastRun;
  return (
    <article className="card ops-card" aria-label="보관 정리">
      <h2>
        보관 정리 <OnOff on={retention.enabled} />
        {retention.dryRun && <span className="badge badge-off">dry-run</span>}
      </h2>
      <dl className="facts">
        <dt>cron (KST)</dt>
        <dd>
          <code>{retention.cron}</code>
          {retention.nextRunAt && <> · 다음 {formatKstSeconds(retention.nextRunAt)}</>}
        </dd>
        <dt>마지막 실행</dt>
        <dd>
          {run ? (
            <>
              {formatKstSeconds(run.at)}{" "}
              {run.result === "SUCCESS" ? (
                <StatusMark level="ok" label={run.dryRun ? "성공 (dry-run)" : "성공"} />
              ) : (
                <StatusMark level="critical" label="실패" />
              )}
            </>
          ) : (
            <span className="muted">재시작 뒤 아직 없음</span>
          )}
        </dd>
        {run?.error && (
          <>
            <dt>오류</dt>
            <dd className="ops-error-text">{run.error}</dd>
          </>
        )}
        {run && run.rows.length > 0 && (
          <>
            <dt>{run.dryRun ? "지울 행" : "지운 행"}</dt>
            <dd>
              {run.rows
                .map((r) => `${TABLE_LABEL[r.table] ?? r.table} ${formatCount(r.rows)}`)
                .join(" · ")}
            </dd>
          </>
        )}
      </dl>
    </article>
  );
}

function SourceTable({ source }: { source: ExternalSourceOps }) {
  const label = sourceLabel(source.source);
  return (
    <section aria-label={label} className="ops-source">
      <h3>
        {label}
        {!source.configured && (
          <span className="muted small"> · 서비스 키 미설정 (호출하지 않음)</span>
        )}
      </h3>
      <div className="table-scroll">
        <table className="ops-table" aria-label={`${label} 호출`}>
          <thead>
            <tr>
              <th rowSpan={2}>오퍼레이션</th>
              <th colSpan={3} className="group">
                오늘
              </th>
              <th colSpan={3} className="group">
                최근 1시간
              </th>
              <th rowSpan={2}>일 한도 사용률</th>
            </tr>
            <tr>
              <th className="num">호출</th>
              <th className="num">실패율</th>
              <th className="num">p50 / p95</th>
              <th className="num">호출</th>
              <th className="num">실패율</th>
              <th className="num">p50 / p95</th>
            </tr>
          </thead>
          <tbody>
            {source.ops.map((op) => (
              <OpRow key={op.op} op={op} />
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function OpRow({ op }: { op: ExternalOp }) {
  const high = isHighFailure(op);
  return (
    <tr className={high ? "ops-failing" : undefined}>
      <td className="ops-op">
        <code>{op.op}</code>
        {high && (
          <div>
            <StatusMark level="critical" label="실패율 높음" />
          </div>
        )}
      </td>
      <WindowCells w={op.today} />
      <WindowCells w={op.lastHour} />
      <td>
        <QuotaMeter op={op} />
      </td>
    </tr>
  );
}

function WindowCells({ w }: { w: CallWindow }) {
  const breakdown = failureBreakdown(w);
  return (
    <>
      <td className="num">{formatCount(w.calls)}</td>
      <td className="num">
        {formatRate(w.failureRate)}
        {breakdown.map((line) => (
          <div key={line} className="muted small">
            {line}
          </div>
        ))}
      </td>
      <td className="num">
        {formatMs(w.p50Ms)} / {formatMs(w.p95Ms)}
      </td>
    </>
  );
}

/** 한도 사용률 막대. 채움이 심각도를 싣고(파랑 → 노랑 → 빨강) 빈 칸은 같은 계열의 옅은 색이다. */
function QuotaMeter({ op }: { op: ExternalOp }) {
  const { dailyLimit, used, usageRate } = op.quota;
  const level = quotaLevel(usageRate);
  const pct = formatRate(usageRate);
  return (
    <div className="meter-row">
      <div
        className={`meter meter-${level}`}
        role="meter"
        aria-label={`${op.op} 일 한도 사용률`}
        aria-valuemin={0}
        aria-valuemax={dailyLimit}
        aria-valuenow={Math.min(used, dailyLimit)}
        aria-valuetext={`${pct} (${formatCount(used)} / ${formatCount(dailyLimit)})`}
      >
        <div className="meter-fill" style={{ width: `${Math.min(usageRate, 1) * 100}%` }} />
      </div>
      <span className="meter-text">
        {pct}{" "}
        <span className="muted small">
          {formatCount(used)} / {formatCount(dailyLimit)}
        </span>
      </span>
      {level !== "ok" && (
        <StatusMark
          level={level}
          label={`한도 ${formatRate(level === "critical" ? QUOTA_CRITICAL : QUOTA_WARNING)}+`}
        />
      )}
    </div>
  );
}
