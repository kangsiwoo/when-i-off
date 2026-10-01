import { Link, useParams } from "react-router";
import type { RouteLeg } from "../api/client";
import { useCommuteRoute } from "../api/queries";
import {
  directionLabel,
  formatDistance,
  formatDuration,
  formatKst,
  legTypeLabel,
  modeLabel,
} from "../format";
import { LazyMap } from "../map/LazyMap";
import { routeOverlay } from "../map/overlay";
import { RouteTabs } from "./RouteTabs";
import { ErrorMessage, Loading } from "./Status";

export function RouteDetailPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <p className="error">잘못된 경로 번호입니다.</p>;
  }
  return <RouteDetail id={id} />;
}

function RouteDetail({ id }: { id: number }) {
  const { data, error, isPending } = useCommuteRoute(id);

  if (isPending) return <Loading />;
  if (error) return <ErrorMessage error={error} />;

  const { route, legs } = data;
  return (
    <section>
      <p>
        <Link to="/">← 경로 목록</Link>
      </p>
      <h1>{route.name}</h1>
      <RouteTabs id={route.id} />
      <dl className="facts">
        <dt>방향</dt>
        <dd>{directionLabel(route.direction)}</dd>
        <dt>상태</dt>
        <dd>{route.isActive ? "사용 중" : "꺼짐"}</dd>
        <dt>출발</dt>
        <dd>
          {route.originLat.toFixed(5)}, {route.originLng.toFixed(5)}
        </dd>
        <dt>도착</dt>
        <dd>
          {route.destinationLat.toFixed(5)}, {route.destinationLng.toFixed(5)}
        </dd>
        <dt>등록</dt>
        <dd>{formatKst(route.createdAt)}</dd>
      </dl>

      <h2>지도</h2>
      <LazyMap label="경로 지도" {...routeOverlay(route, legs)} fitKey={`route-${route.id}`} />

      <h2>
        구간{" "}
        <Link to={`/routes/${route.id}/edit`} className="small">
          편집
        </Link>
      </h2>
      {legs.length === 0 ? (
        <p className="muted">구간이 없습니다.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>순서</th>
              <th>유형</th>
              <th>노선</th>
              <th>승차 → 하차</th>
              <th>거리</th>
              <th>예상 소요</th>
              <th>신호등</th>
            </tr>
          </thead>
          <tbody>
            {[...legs]
              .sort((a, b) => a.seqOrder - b.seqOrder)
              .map((leg) => (
                <LegRow key={leg.id} leg={leg} />
              ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function LegRow({ leg }: { leg: RouteLeg }) {
  const transit = leg.legType === "TRANSIT";
  return (
    <tr>
      <td>{leg.seqOrder}</td>
      <td>
        <span className={`badge badge-${leg.legType.toLowerCase()}`}>
          {legTypeLabel(leg.legType)}
        </span>
      </td>
      <td>
        {transit && leg.transitLine
          ? `${leg.transitLine.name} (${modeLabel(leg.transitLine.mode)})`
          : "—"}
      </td>
      <td>{transit ? `${leg.boardStop?.name ?? "?"} → ${leg.alightStop?.name ?? "?"}` : "—"}</td>
      <td>{transit ? "—" : formatDistance(leg.plannedDistanceM)}</td>
      <td>{formatDuration(leg.plannedTravelSec)}</td>
      <td>{transit ? "—" : `${leg.signalCrossings.length}곳`}</td>
    </tr>
  );
}
