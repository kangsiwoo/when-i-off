import { useState } from "react";
import type { SignalCrossing, TrafficSignal } from "../api/client";
import { useCreateSignal, useReplaceCrossings } from "../api/queries";
import { formatDistance, signalName } from "../format";
import {
  APPROACH_DIR_LABEL,
  APPROACH_DIRS,
  moveDraft,
  SIGNAL_KINDS,
  toCrossingRequests,
  validateCrossingRequests,
  type CrossingDraft,
} from "../legs/legRules";
import { formatLatLng, type LatLng } from "../map/geo";
import { SaveStatus } from "./Status";

/**
 * WALK 구간이 건너는 교차로 순서 (#64). `PUT /route-legs/{id}/signal-crossings`로 구간 저장과 따로 저장한다.
 * 그래서 저장된(id가 있는) 도보 구간에만 연다.
 */
export function SignalCrossingsEditor({
  routeId,
  legId,
  items,
  setItems,
  signals,
  signalsPending,
  picking,
  onPick,
  newSignalAt,
  clearNewSignal,
  onSaved,
}: {
  routeId: number;
  legId: number;
  items: CrossingDraft[];
  setItems: (items: CrossingDraft[]) => void;
  signals: TrafficSignal[] | undefined;
  signalsPending: boolean;
  picking: boolean;
  onPick: () => void;
  newSignalAt: LatLng | null;
  clearNewSignal: () => void;
  onSaved: (crossings: SignalCrossing[]) => void;
}) {
  const save = useReplaceCrossings(routeId, legId);
  const createSignal = useCreateSignal();
  const [problem, setProblem] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [newName, setNewName] = useState("");
  // 방금 등록한 교차로는 근처 목록을 다시 받기 전에도 이름이 보이게 따로 들고 있는다.
  const [created, setCreated] = useState<TrafficSignal[]>([]);
  const byId = new Map([...created, ...(signals ?? [])].map((s) => [s.id, s]));
  const unused = (signals ?? []).filter((s) => !items.some((i) => i.trafficSignalId === s.id));

  const change = (next: CrossingDraft[]) => {
    setSaved(false);
    setItems(next);
  };
  const add = (id: number) =>
    change([...items, { trafficSignalId: id, approachDir: "nt", signalKind: "Pd" }]);
  const patch = (i: number, p: Partial<CrossingDraft>) =>
    change(items.map((it, j) => (j === i ? { ...it, ...p } : it)));

  const submit = () => {
    const body = toCrossingRequests(items);
    const errors = validateCrossingRequests(body);
    setProblem(errors.length ? errors.join(" ") : null);
    if (errors.length) return;
    save.mutate(body, {
      onSuccess: (leg) => {
        setSaved(true);
        onSaved(leg.signalCrossings);
      },
    });
  };

  const register = () => {
    if (!newSignalAt) return;
    createSignal.mutate(
      { ...newSignalAt, ...(newName.trim() ? { name: newName.trim() } : {}) },
      {
        onSuccess: (signal) => {
          setCreated((cs) => [...cs, signal]);
          add(signal.id);
          setNewName("");
          clearNewSignal();
        },
      },
    );
  };

  return (
    <div className="crossings" onClick={(e) => e.stopPropagation()}>
      <h3>건너는 신호등</h3>
      {items.length === 0 ? (
        <p className="muted small">지정한 교차로가 없습니다.</p>
      ) : (
        <ol className="crossing-list">
          {items.map((it, i) => (
            <li key={`${it.trafficSignalId}-${i}`}>
              <span className="crossing-name">
                {signalName(byId.get(it.trafficSignalId), it.trafficSignalId)}
              </span>
              <select
                aria-label={`교차로 ${i + 1} 접근 방향`}
                value={it.approachDir}
                onChange={(e) => patch(i, { approachDir: e.target.value })}
              >
                {APPROACH_DIRS.map((d) => (
                  <option key={d} value={d}>
                    {d} · {APPROACH_DIR_LABEL[d]}
                  </option>
                ))}
              </select>
              <select
                aria-label={`교차로 ${i + 1} 신호 종류`}
                value={it.signalKind}
                onChange={(e) => patch(i, { signalKind: e.target.value })}
              >
                {SIGNAL_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {k}
                    {k === "Pd" ? " · 보행" : ""}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="icon-button"
                aria-label={`교차로 ${i + 1} 위로`}
                disabled={i === 0}
                onClick={() => change(moveDraft(items, i, -1))}
              >
                ↑
              </button>
              <button
                type="button"
                className="link-button"
                onClick={() => change(items.filter((_, j) => j !== i))}
              >
                빼기
              </button>
            </li>
          ))}
        </ol>
      )}

      <p className="small">
        <span className="muted">근처 교차로 (지도의 신호등 마커를 눌러도 추가됩니다)</span>
        {signalsPending && <span className="muted"> · 찾는 중…</span>}
      </p>
      {unused.length > 0 && (
        <ul className="candidates" aria-label="근처 교차로">
          {unused.map((s) => (
            <li key={s.id}>
              <button type="button" className="link-button" onClick={() => add(s.id)}>
                {signalName(s, s.id)} 추가
              </button>{" "}
              <span className="muted small">{formatDistance(s.distanceM)}</span>
            </li>
          ))}
        </ul>
      )}

      <div className="form crossing-actions">
        <button type="button" className="secondary" aria-pressed={picking} onClick={onPick}>
          {picking ? "지도를 누르세요…" : "지도에서 교차로 등록"}
        </button>
        <button type="button" className="primary" disabled={save.isPending} onClick={submit}>
          교차로 저장
        </button>
        <SaveStatus problem={problem} error={save.error} saved={saved} />
      </div>
      {newSignalAt && (
        <div className="form new-signal" role="group" aria-label="새 교차로">
          <span className="small">{formatLatLng(newSignalAt)}</span>
          <div className="field">
            <label htmlFor={`signal-name-${legId}`}>교차로 이름</label>
            <input
              id={`signal-name-${legId}`}
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
            />
          </div>
          <button
            type="button"
            className="primary"
            disabled={createSignal.isPending}
            onClick={register}
          >
            등록하고 추가
          </button>
          <button type="button" className="link-button" onClick={clearNewSignal}>
            취소
          </button>
          <SaveStatus problem={null} error={createSignal.error} saved={false} />
        </div>
      )}
    </div>
  );
}
