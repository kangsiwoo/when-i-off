import { useState } from "react";
import type { DayType, TrafficSignalCycle } from "../api/client";
import { useReplaceSignalCycles, useSignalCycles } from "../api/queries";
import { dayTypeLabel } from "../format";
import {
  DAY_TYPES,
  DEFAULT_CYCLE,
  draftsFromCycles,
  formatBand,
  MAX_CYCLE_SEC,
  MIN_CYCLE_SEC,
  newCycleDraft,
  sortCycles,
  sourceLabel,
  toCycleRequests,
  validateCycleRequests,
  waitPreview,
  type CycleDraft,
} from "../signals/cycleRules";
import { ErrorMessage, Loading, SaveError } from "./Status";

const formatSec = (sec: number) => `${sec.toFixed(1)}초`;
const formatShare = (share: number) => `${Math.round(share * 100)}%`;

function Preview({ cycleSec, redSec }: { cycleSec: number; redSec: number }) {
  const p = waitPreview(cycleSec, redSec);
  if (!p) return <span className="muted small">—</span>;
  return (
    <span className="small">
      평균 대기 <strong>{formatSec(p.meanWaitSec)}</strong> · 적색 {formatShare(p.redShare)}
    </span>
  );
}

function CompactPreview({ cycleSec, redSec }: { cycleSec: number; redSec: number }) {
  const p = waitPreview(cycleSec, redSec);
  return p ? `${formatSec(p.meanWaitSec)} · ${formatShare(p.redShare)}` : "—";
}

/**
 * 교차로 하나의 신호 주기 (#78). 다른 출처 행은 읽기만 하고, 직접 잰 값(`USER_OBSERVED`)만 고쳐
 * `PUT /traffic-signals/{id}/cycles`로 통째로 저장한다. 교차로는 공용이라 이 경로 밖에서도 같은 값을 쓴다.
 */
export function SignalCycleEditor({ signalId, name }: { signalId: number; name: string }) {
  const { data, error, isPending } = useSignalCycles(signalId);
  return (
    <section className="cycle-editor" aria-label={`${name} 신호 주기`}>
      <h4>{name} 신호 주기</h4>
      {isPending ? (
        <Loading />
      ) : error ? (
        <ErrorMessage error={error} />
      ) : (
        <CycleForm key={signalId} signalId={signalId} rows={data} />
      )}
    </section>
  );
}

function CycleForm({ signalId, rows }: { signalId: number; rows: TrafficSignalCycle[] }) {
  const save = useReplaceSignalCycles(signalId);
  const [drafts, setDrafts] = useState<CycleDraft[]>(() => draftsFromCycles(rows));
  const [problems, setProblems] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const others = sortCycles(rows).filter((r) => r.source !== "USER_OBSERVED");

  const change = (next: CycleDraft[]) => {
    setSaved(false);
    setProblems([]);
    setDrafts(next);
  };
  const patch = (key: string, p: Partial<CycleDraft>) =>
    change(drafts.map((d) => (d.key === key ? { ...d, ...p } : d)));

  const submit = () => {
    const body = toCycleRequests(drafts);
    const errors = validateCycleRequests(body);
    setProblems(errors);
    setSaved(false);
    if (errors.length) return;
    save.mutate(body, {
      onSuccess: (next) => {
        setDrafts(draftsFromCycles(next));
        setSaved(true);
      },
    });
  };

  return (
    <>
      {rows.length === 0 && drafts.length === 0 && (
        <p className="muted small">
          저장된 주기가 없어 기본값(주기 {DEFAULT_CYCLE.cycleSec}초, 적색 {DEFAULT_CYCLE.redSec}초,
          평균 대기{" "}
          {formatSec(waitPreview(DEFAULT_CYCLE.cycleSec, DEFAULT_CYCLE.redSec)!.meanWaitSec)}
          )으로 계산합니다.
        </p>
      )}
      {others.length > 0 && (
        <div className="table-scroll">
          <table className="cycle-table" aria-label="다른 출처 주기 (읽기 전용)">
            <thead>
              <tr>
                <th>출처</th>
                <th>요일</th>
                <th>시간대</th>
                <th>주기</th>
                <th>적색</th>
                <th>평균 대기 · 적색</th>
              </tr>
            </thead>
            <tbody>
              {others.map((r) => (
                <tr key={r.id}>
                  <td>{sourceLabel(r.source)}</td>
                  <td>{dayTypeLabel(r.dayType)}</td>
                  <td>{formatBand(r.timeBandStart, r.timeBandEnd)}</td>
                  <td>{r.cycleDurationSec}초</td>
                  <td>{r.redDurationSec}초</td>
                  <td>
                    <CompactPreview cycleSec={r.cycleDurationSec} redSec={r.redDurationSec} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="small muted">
        직접 잰 값 — 시간대는 KST, 끝 시각은 포함하지 않습니다. 같은 시각에 다른 출처 값이 있어도
        직접 잰 값을 먼저 씁니다.
      </p>
      {drafts.length > 0 && (
        <ol className="cycle-rows">
          {drafts.map((d, i) => {
            const label = `${i + 1}행`;
            return (
              <li key={d.key} className="cycle-row">
                <select
                  aria-label={`${label} 요일`}
                  value={d.dayType}
                  onChange={(e) => patch(d.key, { dayType: e.target.value as DayType })}
                >
                  {DAY_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {dayTypeLabel(t)}
                    </option>
                  ))}
                </select>
                <input
                  type="time"
                  step={1}
                  aria-label={`${label} 시작`}
                  value={d.start}
                  onChange={(e) => patch(d.key, { start: e.target.value })}
                />
                <span aria-hidden="true">–</span>
                <input
                  type="time"
                  step={1}
                  aria-label={`${label} 끝`}
                  value={d.end}
                  onChange={(e) => patch(d.key, { end: e.target.value })}
                />
                <label className="cycle-num">
                  주기
                  <input
                    type="number"
                    inputMode="numeric"
                    min={MIN_CYCLE_SEC}
                    max={MAX_CYCLE_SEC}
                    aria-label={`${label} 주기(초)`}
                    value={d.cycleSec}
                    onChange={(e) => patch(d.key, { cycleSec: e.target.value })}
                  />
                  초
                </label>
                <label className="cycle-num">
                  적색
                  <input
                    type="number"
                    inputMode="numeric"
                    min={1}
                    aria-label={`${label} 적색(초)`}
                    value={d.redSec}
                    onChange={(e) => patch(d.key, { redSec: e.target.value })}
                  />
                  초
                </label>
                <Preview cycleSec={Number(d.cycleSec)} redSec={Number(d.redSec)} />
                <button
                  type="button"
                  className="link-button"
                  aria-label={`${label} 빼기`}
                  onClick={() => change(drafts.filter((x) => x.key !== d.key))}
                >
                  빼기
                </button>
              </li>
            );
          })}
        </ol>
      )}
      <div className="form crossing-actions">
        <button
          type="button"
          className="secondary"
          onClick={() => change([...drafts, newCycleDraft(drafts.at(-1)?.dayType)])}
        >
          + 시간대
        </button>
        <button type="button" className="primary" disabled={save.isPending} onClick={submit}>
          주기 저장
        </button>
      </div>
      {problems.length > 0 && (
        <div role="alert" className="error">
          저장하기 전에 고칠 것:
          <ul>
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      )}
      {save.error && problems.length === 0 && <SaveError error={save.error} />}
      {saved && <p role="status">저장했습니다.</p>}
    </>
  );
}
