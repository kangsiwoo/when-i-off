import type { ReactNode } from "react";
import type { TransitLine, TransitStop } from "../api/client";
import { formatDistance, modeLabel } from "../format";
import type { TransitDraft, WalkDraft } from "../legs/legRules";
import { formatLatLng } from "../map/geo";
import { LinePicker } from "./LinePicker";

export type PickTarget = "walk-start" | "walk-end" | "board" | "alight" | "signal";
export type StopWhich = "board" | "alight";

interface CardShellProps {
  index: number;
  count: number;
  label: string;
  type: "walk" | "transit";
  selected: boolean;
  onSelect: () => void;
  onMove: (delta: -1 | 1) => void;
  onRemove: () => void;
  children: ReactNode;
}

function CardShell(p: CardShellProps) {
  return (
    <li
      className={`card leg-card${p.selected ? " leg-card-selected" : ""}`}
      aria-label={`구간 ${p.index + 1}`}
      aria-current={p.selected ? "true" : undefined}
      onClick={p.onSelect}
    >
      <div className="leg-card-head">
        <strong>{p.index + 1}</strong>
        <span className={`badge badge-${p.type}`}>{p.label}</span>
        <span className="leg-card-tools">
          <button
            type="button"
            className="icon-button"
            aria-label="위로"
            disabled={p.index === 0}
            onClick={() => p.onMove(-1)}
          >
            ↑
          </button>
          <button
            type="button"
            className="icon-button"
            aria-label="아래로"
            disabled={p.index === p.count - 1}
            onClick={() => p.onMove(1)}
          >
            ↓
          </button>
          <button type="button" className="link-button" onClick={p.onRemove}>
            삭제
          </button>
        </span>
      </div>
      {p.children}
    </li>
  );
}

type ShellProps = Omit<CardShellProps, "label" | "type" | "children">;

export function WalkLegCard({
  draft,
  picking,
  onPick,
  onDistance,
  onStraightDistance,
  onInsertTransit,
  children,
  ...shell
}: ShellProps & {
  draft: WalkDraft;
  picking: PickTarget | null;
  onPick: (t: PickTarget) => void;
  onDistance: (m: number | null) => void;
  onStraightDistance: () => void;
  onInsertTransit: () => void;
  children?: ReactNode;
}) {
  return (
    <CardShell {...shell} label="도보" type="walk">
      <dl className="facts leg-facts">
        {(["start", "end"] as const).map((which) => {
          const p = draft[which];
          const target = which === "start" ? "walk-start" : "walk-end";
          return [
            <dt key={`${which}-t`}>{which === "start" ? "시작" : "끝"}</dt>,
            <dd key={`${which}-d`}>
              {p ? formatLatLng(p) : <span className="muted">미정</span>}{" "}
              <button
                type="button"
                className="link-button"
                aria-pressed={picking === target}
                onClick={() => onPick(target)}
              >
                {picking === target ? "지도를 누르세요…" : "지도에서 찍기"}
              </button>
            </dd>,
          ];
        })}
      </dl>
      <div className="form leg-fields">
        <div className="field">
          <label htmlFor={`dist-${draft.key}`}>계획 거리 (m)</label>
          <input
            id={`dist-${draft.key}`}
            type="number"
            min={1}
            value={draft.plannedDistanceM ?? ""}
            onChange={(e) => onDistance(e.target.value === "" ? null : Number(e.target.value))}
          />
        </div>
        <button
          type="button"
          className="secondary"
          disabled={!draft.start || !draft.end}
          title="직접 넣은 거리는 끝점을 옮겨도 그대로 둡니다. 직선거리로 되돌립니다."
          onClick={onStraightDistance}
        >
          직선거리로
        </button>
        <button type="button" className="secondary" onClick={onInsertTransit}>
          뒤에 대중교통 넣기
        </button>
      </div>
      {children}
    </CardShell>
  );
}

export function TransitLegCard({
  draft,
  picking,
  onPick,
  onLine,
  onTravelSec,
  stopSearch,
  candidates,
  candidatesPending,
  onNearby,
  onStop,
  ...shell
}: ShellProps & {
  draft: TransitDraft;
  picking: PickTarget | null;
  onPick: (t: PickTarget) => void;
  onLine: (line: TransitLine) => void;
  onTravelSec: (sec: number | null) => void;
  /** 이 구간에서 근처 정류장을 찾는 중이면 어느 쪽인지. */
  stopSearch: StopWhich | null;
  candidates: TransitStop[] | undefined;
  candidatesPending: boolean;
  onNearby: (which: StopWhich) => void;
  onStop: (which: StopWhich, stop: TransitStop) => void;
}) {
  return (
    <CardShell {...shell} label="대중교통" type="transit">
      <LinePicker line={draft.line} onLine={onLine} keyPrefix={draft.key} />
      {(["board", "alight"] as const).map((which) => {
        const stop = which === "board" ? draft.boardStop : draft.alightStop;
        const name = which === "board" ? "승차" : "하차";
        return (
          <div key={which} className="stop-row">
            <span className="stop-label">{name}</span>
            <span>
              {stop ? (
                `${stop.name} (${modeLabel(stop.mode)})`
              ) : (
                <span className="muted">미선택</span>
              )}
            </span>
            <button type="button" className="link-button" onClick={() => onNearby(which)}>
              {which === "board" ? "앞 도보 끝 근처" : "뒤 도보 시작 근처"}
            </button>
            <button
              type="button"
              className="link-button"
              aria-pressed={picking === which}
              aria-label={`${name} 정류장 위치를 지도에서 찍기`}
              onClick={() => onPick(which)}
            >
              {picking === which ? "지도를 누르세요…" : "지도에서 위치 찍기"}
            </button>
            {stopSearch === which && (
              <StopCandidates
                name={name}
                line={draft.line}
                stops={candidates}
                pending={candidatesPending}
                onStop={(s) => onStop(which, s)}
              />
            )}
          </div>
        );
      })}
      <div className="form leg-fields">
        <div className="field">
          <label htmlFor={`travel-${draft.key}`}>계획 소요 (분)</label>
          <input
            id={`travel-${draft.key}`}
            type="number"
            min={1}
            step="any"
            value={draft.plannedTravelSec == null ? "" : draft.plannedTravelSec / 60}
            onChange={(e) =>
              onTravelSec(e.target.value === "" ? null : Math.round(Number(e.target.value) * 60))
            }
          />
        </div>
      </div>
    </CardShell>
  );
}

function StopCandidates({
  name,
  line,
  stops,
  pending,
  onStop,
}: {
  name: string;
  line: TransitLine | null;
  stops: TransitStop[] | undefined;
  pending: boolean;
  onStop: (s: TransitStop) => void;
}) {
  if (pending) return <p className="muted small candidates">근처 정류장을 찾는 중…</p>;
  if (!stops || stops.length === 0) {
    return (
      <p className="muted small candidates">
        근처에 {line ? `${modeLabel(line.mode)} ` : ""}정류장이 없습니다. 지도에서 다른 곳을 찍어
        보세요.
      </p>
    );
  }
  return (
    <ul className="candidates" aria-label={`${name} 정류장 후보`}>
      {stops.map((s) => (
        <li key={s.id}>
          <button type="button" className="link-button" onClick={() => onStop(s)}>
            {s.name}
          </button>{" "}
          <span className="muted small">
            {modeLabel(s.mode)} · {formatDistance(s.distanceM)}
          </span>
        </li>
      ))}
    </ul>
  );
}
