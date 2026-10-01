import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import type { BoardingAttempt, CommuteTrip, RouteLeg } from "../api/client";
import { useCommuteRoute, useCommuteTrips, type TripFilter } from "../api/queries";
import { formatDuration, formatKst, resultLabel, transitLegLabel } from "../format";
import { buildTimeline, summarizeTrip } from "../trips/timeline";
import { AttemptForm } from "./AttemptForm";
import { ErrorMessage, Loading } from "./Status";
import { TripResult } from "./TripResult";
import { TripGpsMap } from "./TripGpsMap";
import { TripTimeline } from "./TripTimeline";
import { TripTimesForm } from "./TripTimesForm";

export function TripDetailPage() {
  const id = Number(useParams().id);
  const [params] = useSearchParams();
  if (!Number.isInteger(id) || id <= 0) {
    return <p className="error">잘못된 기록 번호입니다.</p>;
  }
  // trip 단건 조회 API는 없다. 목록 링크가 넘긴 경로·날짜로 좁혀 목록 조회에서 꺼낸다 (없으면 전체에서).
  const routeId = Number(params.get("routeId"));
  const date = params.get("date");
  const filter: TripFilter = {
    ...(Number.isInteger(routeId) && routeId > 0 ? { routeId } : {}),
    ...(date ? { from: date, to: date } : {}),
  };
  return <TripLookup id={id} filter={filter} />;
}

function TripLookup({ id, filter }: { id: number; filter: TripFilter }) {
  const { data, error, isPending } = useCommuteTrips(filter);
  if (isPending) return <Loading />;
  if (error) return <ErrorMessage error={error} />;
  const trip = data.find((t) => t.id === id);
  if (!trip) return <p className="error">기록 {id}을(를) 찾을 수 없습니다.</p>;
  return <TripDetail trip={trip} />;
}

function TripDetail({ trip }: { trip: CommuteTrip }) {
  const route = useCommuteRoute(trip.routeId);
  // 경로 상세(구간 이름·순서)를 못 받아도 기록만으로 타임라인은 그린다.
  const legs = route.data?.legs;
  const summary = summarizeTrip(trip, legs);

  return (
    <section>
      <p>
        <Link to={`/trips?routeId=${trip.routeId}`}>← 이동 기록</Link>
      </p>
      <h1>
        {trip.tripDate} · {route.data?.route.name ?? `경로 #${trip.routeId}`}
      </h1>
      <dl className="facts">
        <dt>총 소요</dt>
        <dd>{formatDuration(summary.totalSec)}</dd>
        <dt>결과</dt>
        <dd>
          <TripResult summary={summary} />
        </dd>
        <dt>기록 생성</dt>
        <dd>{formatKst(trip.createdAt)}</dd>
      </dl>
      {route.error && <ErrorMessage error={route.error} />}

      <h2>타임라인 (KST)</h2>
      <TripTimeline events={buildTimeline(trip, legs)} />

      <h2>GPS 트랙</h2>
      <TripGpsMap trip={trip} legs={legs} />

      <h2>trip 시각 보정</h2>
      <TripTimesForm trip={trip} />

      <h2>탑승 시도 보정</h2>
      {trip.boardingAttempts.length === 0 ? (
        <p className="muted">탑승 시도 기록이 없습니다.</p>
      ) : (
        <ul className="attempts">
          {trip.boardingAttempts.map((a) => (
            <AttemptItem key={a.id} attempt={a} legs={legs} />
          ))}
        </ul>
      )}
    </section>
  );
}

function AttemptItem({ attempt, legs }: { attempt: BoardingAttempt; legs?: RouteLeg[] }) {
  const [open, setOpen] = useState(false);
  const leg = legs?.find((l) => l.id === attempt.routeLegId);
  return (
    <li className="card attempt">
      <div className="attempt-head">
        <span>
          {transitLegLabel(leg, attempt.routeLegId)} · {attempt.attemptSeq}번째 차 ·{" "}
          {resultLabel(attempt.result)}
          {attempt.notes && <span className="muted"> · {attempt.notes}</span>}
        </span>
        <button type="button" className="link-button" onClick={() => setOpen(!open)}>
          {open ? "닫기" : "보정"}
        </button>
      </div>
      {open && <AttemptForm attempt={attempt} />}
    </li>
  );
}
