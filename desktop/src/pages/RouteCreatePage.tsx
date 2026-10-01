import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import type { CommuteRoute } from "../api/client";
import { useCreateRoute } from "../api/queries";
import { formatLatLng, isValidLatLng, type LatLng } from "../map/geo";
import { LazyMap } from "../map/LazyMap";
import type { MapMarker } from "../map/types";
import { SaveStatus } from "./Status";

type Target = "origin" | "destination";
const TARGET_LABEL: Record<Target, string> = { origin: "출발(집)", destination: "도착" };

/** 경로 생성 (#64): 이름·방향을 넣고 지도에서 출발/도착을 찍는다. 만들면 구간 편집으로 간다. */
export function RouteCreatePage() {
  const navigate = useNavigate();
  const create = useCreateRoute();
  const [name, setName] = useState("");
  const [direction, setDirection] = useState<CommuteRoute["direction"]>("TO_WORK");
  const [points, setPoints] = useState<Record<Target, LatLng | null>>({
    origin: null,
    destination: null,
  });
  const [target, setTarget] = useState<Target>("origin");
  const [problem, setProblem] = useState<string | null>(null);

  const place = (t: Target, p: LatLng) => setPoints((prev) => ({ ...prev, [t]: p }));
  const onMapClick = (p: LatLng) => {
    place(target, p);
    // 출발을 찍으면 다음 클릭은 도착을 찍는다.
    if (target === "origin" && !points.destination) setTarget("destination");
  };

  const markers: MapMarker[] = (["origin", "destination"] as const).flatMap((t) => {
    const p = points[t];
    return p
      ? [
          {
            id: t,
            position: p,
            kind: t,
            text: t === "origin" ? "집" : "끝",
            tooltip: TARGET_LABEL[t],
            onDragEnd: (q: LatLng) => place(t, q),
          },
        ]
      : [];
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const { origin, destination } = points;
    if (name.trim() === "") return setProblem("이름을 넣으세요.");
    if (!origin || !destination) return setProblem("지도에서 출발과 도착을 찍으세요.");
    if (!isValidLatLng(origin) || !isValidLatLng(destination)) {
      return setProblem("좌표가 WGS84 범위를 벗어났습니다.");
    }
    setProblem(null);
    create.mutate(
      {
        name: name.trim(),
        direction,
        originLat: origin.lat,
        originLng: origin.lng,
        destinationLat: destination.lat,
        destinationLng: destination.lng,
        isActive: true,
      },
      { onSuccess: (route) => void navigate(`/routes/${route.id}/edit`) },
    );
  };

  return (
    <section>
      <p>
        <Link to="/">← 경로 목록</Link>
      </p>
      <h1>새 경로</h1>
      <form className="card form" onSubmit={submit} aria-label="새 경로">
        <div className="field">
          <label htmlFor="route-name">이름</label>
          <input
            id="route-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="평일 출근"
          />
        </div>
        <div className="field">
          <label htmlFor="route-direction">방향</label>
          <select
            id="route-direction"
            value={direction}
            onChange={(e) => setDirection(e.target.value as CommuteRoute["direction"])}
          >
            <option value="TO_WORK">출근</option>
            <option value="TO_HOME">퇴근</option>
          </select>
        </div>
        <fieldset className="segmented">
          <legend>지도 클릭으로 찍을 곳</legend>
          {(["origin", "destination"] as const).map((t) => (
            <label key={t}>
              <input
                type="radio"
                name="target"
                checked={target === t}
                onChange={() => setTarget(t)}
              />
              {TARGET_LABEL[t]}
            </label>
          ))}
        </fieldset>
        <dl className="facts point-facts">
          <dt>출발</dt>
          <dd>{points.origin ? formatLatLng(points.origin) : "미정"}</dd>
          <dt>도착</dt>
          <dd>{points.destination ? formatLatLng(points.destination) : "미정"}</dd>
        </dl>
        <button type="submit" disabled={create.isPending}>
          만들고 구간 편집
        </button>
        <SaveStatus problem={problem} error={create.error} saved={false} />
      </form>
      <p className="muted small">
        지도를 눌러 {TARGET_LABEL[target]}을 찍습니다. 찍은 마커는 끌어서 옮길 수 있습니다.
      </p>
      <LazyMap
        label="출발·도착 지도"
        markers={markers}
        onMapClick={onMapClick}
        fitTo={[]}
        picking
      />
    </section>
  );
}
