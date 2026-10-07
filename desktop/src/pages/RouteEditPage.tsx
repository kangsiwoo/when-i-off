import { useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import type { CommuteRouteDetail, SignalCrossing, TransitStop } from "../api/client";
import { useCommuteRoute, useNearbySignals, useNearbyStops, useReplaceLegs } from "../api/queries";
import {
  draftsFromLegs,
  moveDraft,
  newTransit,
  newWalk,
  toLegRequests,
  validateDrafts,
  type CrossingDraft,
  type LegDraft,
  type TransitDraft,
  type WalkDraft,
} from "../legs/legRules";
import { signalName } from "../format";
import { haversineM, midpoint, walkDistanceM, type LatLng } from "../map/geo";
import { LazyMap } from "../map/LazyMap";
import { lineTone } from "../map/lineTone";
import type { MapLine, MapMarker } from "../map/types";
import { TransitLegCard, WalkLegCard, type PickTarget, type StopWhich } from "./LegCards";
import { RouteTabs } from "./RouteTabs";
import { SignalCrossingsEditor } from "./SignalCrossingsEditor";
import { ErrorMessage, Loading, SaveError } from "./Status";

const STOP_RADIUS_M = 800;

export function RouteEditPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <p className="error">잘못된 경로 번호입니다.</p>;
  }
  return <RouteEdit id={id} />;
}

function RouteEdit({ id }: { id: number }) {
  const { data, error, isPending } = useCommuteRoute(id);
  if (isPending) return <Loading />;
  if (error) return <ErrorMessage error={error} />;
  return (
    <section>
      <p>
        <Link to="/">← 경로 목록</Link>
      </p>
      <h1>{data.route.name}</h1>
      <RouteTabs id={id} />
      {/* 초안은 처음 한 번만 서버 값에서 만든다 — 다른 저장(교차로 등)으로 상세가 다시 와도 편집 중인 값을 덮지 않는다. */}
      <LegEditor detail={data} />
    </section>
  );
}

const pointOf = (s: TransitStop | null): LatLng | null => (s ? { lat: s.lat, lng: s.lng } : null);
const same = (a: LatLng | null, b: LatLng | null) =>
  a != null && b != null && a.lat === b.lat && a.lng === b.lng;

/** 끝점을 옮긴 WALK 초안. 사용자가 거리를 직접 넣지 않았으면 직선거리로 다시 채운다. */
function withPoint(d: WalkDraft, which: "start" | "end", p: LatLng | null): WalkDraft {
  const next = { ...d, [which]: p };
  if (!next.distanceEdited) {
    next.plannedDistanceM = next.start && next.end ? walkDistanceM(next.start, next.end) : null;
  }
  return next;
}

type Pick = { key: string; target: PickTarget } | null;
type StopSearch = { key: string; which: StopWhich; at: LatLng } | null;

function LegEditor({ detail }: { detail: CommuteRouteDetail }) {
  const { route } = detail;
  const origin = { lat: route.originLat, lng: route.originLng };
  const destination = { lat: route.destinationLat, lng: route.destinationLng };
  const replace = useReplaceLegs(route.id);

  const initial = () =>
    detail.legs.length > 0 ? draftsFromLegs(detail.legs) : [newWalk(origin, destination)];
  const [drafts, setDrafts] = useState<LegDraft[]>(initial);
  const [baseline, setBaseline] = useState(() =>
    JSON.stringify(toLegRequests(draftsFromLegs(detail.legs))),
  );
  const [selected, setSelected] = useState<string | null>(null);
  const [pick, setPick] = useState<Pick>(null);
  const [stopSearch, setStopSearch] = useState<StopSearch>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const [crossings, setCrossings] = useState<{ key: string; items: CrossingDraft[] } | null>(null);
  const [newSignalAt, setNewSignalAt] = useState<LatLng | null>(null);
  const [fitKey] = useState(() => `route-${route.id}`);

  const dirty = JSON.stringify(toLegRequests(drafts)) !== baseline;
  const selectedDraft = drafts.find((d) => d.key === selected);
  const selectedWalk = selectedDraft?.legType === "WALK" ? selectedDraft : undefined;

  const edit = (next: LegDraft[]) => {
    setSaved(false);
    setProblems([]);
    setDrafts(next);
  };
  const update = (key: string, f: (d: LegDraft) => LegDraft) =>
    edit(drafts.map((d) => (d.key === key ? f(d) : d)));
  const updateWalk = (key: string, f: (d: WalkDraft) => WalkDraft) =>
    update(key, (d) => (d.legType === "WALK" ? f(d) : d));
  const updateTransit = (key: string, f: (d: TransitDraft) => TransitDraft) =>
    update(key, (d) => (d.legType === "TRANSIT" ? f(d) : d));

  // --- 근처 정류장 / 교차로 ---
  const stopDraft = drafts.find((d) => d.key === stopSearch?.key);
  const stopMode = stopDraft?.legType === "TRANSIT" ? stopDraft.line?.mode : undefined;
  const nearbyStops = useNearbyStops(
    stopSearch ? { ...stopSearch.at, radiusM: STOP_RADIUS_M } : null,
    stopMode,
  );
  const signalArea = useMemo(() => {
    if (!selectedWalk?.id || !selectedWalk.start || !selectedWalk.end) return null;
    const { start, end } = selectedWalk;
    // 구간을 덮는 원: 가운데에서 반 길이 + 여유 150 m.
    return { ...midpoint(start, end), radiusM: Math.round(haversineM(start, end) / 2 + 150) };
  }, [selectedWalk]);
  const nearbySignals = useNearbySignals(signalArea);
  const crossingItems: CrossingDraft[] =
    crossings && crossings.key === selected
      ? crossings.items
      : (selectedWalk?.signalCrossings ?? []).map(
          ({ trafficSignalId, approachDir, signalKind }) => ({
            trafficSignalId,
            approachDir,
            signalKind,
          }),
        );
  const setCrossingItems = (items: CrossingDraft[]) =>
    selected && setCrossings({ key: selected, items });

  // --- 동작 ---
  const indexOf = (key: string) => drafts.findIndex((d) => d.key === key);

  const onMapClick = (p: LatLng) => {
    if (!pick) return;
    if (pick.target === "walk-start" || pick.target === "walk-end") {
      updateWalk(pick.key, (d) => withPoint(d, pick.target === "walk-start" ? "start" : "end", p));
    } else if (pick.target === "board" || pick.target === "alight") {
      setStopSearch({ key: pick.key, which: pick.target, at: p });
    } else {
      setNewSignalAt(p);
    }
    setPick(null);
  };

  const togglePick = (key: string, target: PickTarget) =>
    setPick(pick?.key === key && pick.target === target ? null : { key, target });

  const nearbyAnchor = (key: string, which: StopWhich): LatLng => {
    const i = indexOf(key);
    const neighbour = drafts[which === "board" ? i - 1 : i + 1];
    const d = drafts[i] as TransitDraft;
    if (neighbour?.legType === "WALK") {
      const p = which === "board" ? neighbour.end : neighbour.start;
      if (p) return p;
    }
    return (
      pointOf(which === "board" ? d.boardStop : d.alightStop) ??
      (which === "board" ? origin : destination)
    );
  };

  /** 정류장을 고르면, 비어 있거나 예전 정류장에 붙어 있던 옆 도보 끝점을 그 정류장으로 옮긴다. */
  const chooseStop = (key: string, which: StopWhich, stop: TransitStop) => {
    const i = indexOf(key);
    const d = drafts[i] as TransitDraft;
    const old = pointOf(which === "board" ? d.boardStop : d.alightStop);
    const ni = which === "board" ? i - 1 : i + 1;
    const end = which === "board" ? "end" : "start";
    edit(
      drafts.map((x, j) => {
        if (j === i) return { ...d, [which === "board" ? "boardStop" : "alightStop"]: stop };
        if (j === ni && x.legType === "WALK" && (x[end] == null || same(x[end], old))) {
          return withPoint(x, end, pointOf(stop));
        }
        return x;
      }),
    );
    setStopSearch(null);
  };

  const insertTransitAfter = (key: string) => {
    const i = indexOf(key);
    const w = drafts[i] as WalkDraft;
    const t = newTransit();
    edit([
      ...drafts.slice(0, i),
      withPoint({ ...w, distanceEdited: false }, "end", null),
      t,
      newWalk(null, w.end),
      ...drafts.slice(i + 1),
    ]);
    setSelected(t.key);
  };

  const lastPoint = (): LatLng | null => {
    const last = drafts[drafts.length - 1];
    if (!last) return origin;
    return last.legType === "WALK" ? last.end : pointOf(last.alightStop);
  };

  const remove = (key: string) => {
    edit(drafts.filter((d) => d.key !== key));
    if (selected === key) setSelected(null);
  };

  const save = () => {
    const errors = validateDrafts(drafts);
    setProblems(errors);
    setSaved(false);
    if (errors.length > 0) return;
    const selectedIndex = selected ? indexOf(selected) : -1;
    replace.mutate(toLegRequests(drafts), {
      onSuccess: (res) => {
        const next = draftsFromLegs(res.legs);
        setDrafts(next);
        setBaseline(JSON.stringify(toLegRequests(next)));
        setSelected(next[selectedIndex]?.key ?? null);
        setCrossings(null);
        setSaved(true);
      },
    });
  };

  const onCrossingsSaved = (key: string, stored: SignalCrossing[]) => {
    // 교차로는 따로 저장되므로 초안과 기준선 양쪽에 반영한다 (구간 변경 여부와 무관).
    setDrafts((ds) =>
      ds.map((d) =>
        d.key === key && d.legType === "WALK" ? { ...d, signalCrossings: stored } : d,
      ),
    );
    setCrossings(null);
  };

  // --- 지도 ---
  const { markers, lines } = buildMap();

  function buildMap() {
    const markers: MapMarker[] = [
      { id: "origin", position: origin, kind: "origin", text: "집", tooltip: "출발" },
      {
        id: "destination",
        position: destination,
        kind: "destination",
        text: "끝",
        tooltip: "도착",
      },
    ];
    const lines: MapLine[] = [];
    drafts.forEach((d, i) => {
      const n = i + 1;
      const isSel = d.key === selected;
      const select = () => setSelected(d.key);
      if (d.legType === "WALK") {
        if (d.start && d.end) {
          lines.push({ id: d.key, positions: [d.start, d.end], kind: "walk", selected: isSel });
        }
        for (const which of ["start", "end"] as const) {
          const p = d[which];
          if (!p) continue;
          markers.push({
            id: `${d.key}-${which}`,
            position: p,
            kind: which === "start" ? "walk-start" : "walk-end",
            text: String(n),
            tooltip: `구간 ${n} 도보 ${which === "start" ? "시작" : "끝"} (끌어서 옮기기)`,
            selected: isSel,
            onDragEnd: (q) => updateWalk(d.key, (x) => withPoint(x, which, q)),
            onClick: select,
          });
        }
      } else {
        const b = pointOf(d.boardStop);
        const a = pointOf(d.alightStop);
        if (b && a)
          lines.push({
            id: d.key,
            positions: [b, a],
            kind: "transit",
            tone: lineTone(d.line),
            selected: isSel,
          });
        for (const [stop, label] of [
          [d.boardStop, "승차"],
          [d.alightStop, "하차"],
        ] as const) {
          if (!stop) continue;
          markers.push({
            id: `${d.key}-${label}`,
            position: pointOf(stop)!,
            kind: "stop",
            text: String(n),
            tooltip: `구간 ${n} ${label}: ${stop.name}`,
            selected: isSel,
            onClick: select,
          });
        }
      }
    });
    if (stopSearch && nearbyStops.data) {
      for (const s of nearbyStops.data) {
        markers.push({
          id: `cand-${s.id}`,
          position: pointOf(s)!,
          kind: "candidate",
          tooltip: `${s.name} — 눌러서 ${stopSearch.which === "board" ? "승차" : "하차"} 정류장으로`,
          onClick: () => chooseStop(stopSearch.key, stopSearch.which, s),
        });
      }
    }
    if (selectedWalk?.id != null && nearbySignals.data) {
      for (const s of nearbySignals.data) {
        const crossed = crossingItems.some((c) => c.trafficSignalId === s.id);
        markers.push({
          id: `signal-${s.id}`,
          position: { lat: s.lat, lng: s.lng },
          kind: crossed ? "signal-crossed" : "signal",
          tooltip: `${signalName(s, s.id)}${crossed ? " (지정됨)" : " — 눌러서 추가"}`,
          onClick: crossed
            ? undefined
            : () =>
                setCrossingItems([
                  ...crossingItems,
                  { trafficSignalId: s.id, approachDir: "nt", signalKind: "Pd" },
                ]),
        });
      }
    }
    if (newSignalAt) {
      markers.push({
        id: "new-signal",
        position: newSignalAt,
        kind: "signal",
        text: "+",
        tooltip: "새 교차로",
      });
    }
    return { markers, lines };
  }

  // 구간을 고르면 그 구간으로 맞춘다 (도보 끝점·교차로를 찍기 좋게). 아무것도 안 골랐거나 점이 없으면 경로 전체.
  const selectedPoints: LatLng[] = !selectedDraft
    ? []
    : selectedDraft.legType === "WALK"
      ? [selectedDraft.start, selectedDraft.end].filter((p): p is LatLng => p != null)
      : [pointOf(selectedDraft.boardStop), pointOf(selectedDraft.alightStop)].filter(
          (p): p is LatLng => p != null,
        );
  const fitPoints = selectedPoints.length > 0 ? selectedPoints : [origin, destination];

  return (
    <div className="editor">
      <div className="editor-map">
        <LazyMap
          label="구간 지도"
          markers={markers}
          lines={lines}
          onMapClick={onMapClick}
          fitTo={fitPoints}
          fitKey={`${fitKey}-${selectedPoints.length > 0 ? selected : "all"}`}
          picking={pick != null}
        />
        <p className="muted small">
          도보 끝점은 끌어서 옮깁니다. “지도에서 찍기”를 누른 뒤 지도를 누르면 그 자리에 놓습니다.
        </p>
      </div>
      <div className="editor-panel">
        <ol className="leg-list">
          {drafts.map((d, i) => {
            const shell = {
              index: i,
              count: drafts.length,
              selected: d.key === selected,
              onSelect: () => setSelected(d.key),
              onMove: (delta: -1 | 1) => edit(moveDraft(drafts, i, delta)),
              onRemove: () => remove(d.key),
            };
            const picking = pick?.key === d.key ? pick.target : null;
            if (d.legType === "WALK") {
              return (
                <WalkLegCard
                  key={d.key}
                  {...shell}
                  draft={d}
                  picking={picking}
                  onPick={(t) => togglePick(d.key, t)}
                  onDistance={(m) =>
                    updateWalk(d.key, (x) => ({ ...x, plannedDistanceM: m, distanceEdited: true }))
                  }
                  onStraightDistance={() =>
                    updateWalk(d.key, (x) =>
                      withPoint({ ...x, distanceEdited: false }, "start", x.start),
                    )
                  }
                  onInsertTransit={() => insertTransitAfter(d.key)}
                >
                  {d.key === selected &&
                    (d.id == null ? (
                      <p className="muted small">신호등 교차로는 구간을 저장한 뒤 지정합니다.</p>
                    ) : (
                      <SignalCrossingsEditor
                        routeId={route.id}
                        legId={d.id}
                        items={crossingItems}
                        setItems={setCrossingItems}
                        signals={nearbySignals.data}
                        signalsPending={nearbySignals.isFetching}
                        picking={picking === "signal"}
                        onPick={() => togglePick(d.key, "signal")}
                        newSignalAt={newSignalAt}
                        clearNewSignal={() => setNewSignalAt(null)}
                        onSaved={(c) => onCrossingsSaved(d.key, c)}
                      />
                    ))}
                </WalkLegCard>
              );
            }
            return (
              <TransitLegCard
                key={d.key}
                {...shell}
                draft={d}
                picking={picking}
                onPick={(t) => togglePick(d.key, t)}
                onLine={(line) => updateTransit(d.key, (x) => ({ ...x, line }))}
                onTravelSec={(sec) =>
                  updateTransit(d.key, (x) => ({ ...x, plannedTravelSec: sec }))
                }
                stopSearch={stopSearch?.key === d.key ? stopSearch.which : null}
                candidates={nearbyStops.data}
                candidatesPending={nearbyStops.isFetching}
                onNearby={(which) =>
                  setStopSearch({ key: d.key, which, at: nearbyAnchor(d.key, which) })
                }
                onStop={(which, stop) => chooseStop(d.key, which, stop)}
              />
            );
          })}
        </ol>
        <div className="form editor-actions">
          <button
            type="button"
            className="secondary"
            onClick={() => edit([...drafts, newWalk(lastPoint(), destination)])}
          >
            + 도보
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => edit([...drafts, newTransit()])}
          >
            + 대중교통
          </button>
          <button type="button" className="primary" disabled={replace.isPending} onClick={save}>
            구간 저장
          </button>
          {dirty && <span className="badge badge-incomplete">저장 안 된 변경</span>}
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
        {replace.error && problems.length === 0 && <SaveError error={replace.error} />}
        {saved && !dirty && <p role="status">저장했습니다.</p>}
      </div>
    </div>
  );
}
