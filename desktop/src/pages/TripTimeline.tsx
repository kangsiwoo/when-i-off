import type { BoardingAttempt } from "../api/client";
import { formatSignedSec, transitLegLabel } from "../format";
import { formatKstTime } from "../kst";
import { predictionErrorSec, type LegRef, type TimelineEvent } from "../trips/timeline";

export function TripTimeline({ events }: { events: TimelineEvent[] }) {
  return (
    <ol className="timeline" aria-label="타임라인">
      {events.map((event, i) => (
        <li key={i} className={`timeline-item timeline-${event.kind.toLowerCase()}`}>
          <time className="timeline-time">{event.at ? formatKstTime(event.at) : "—"}</time>
          <div>
            <div className="timeline-label">{label(event)}</div>
            {"attempt" in event && event.kind !== "ALIGHTED" && (
              <Prediction attempt={event.attempt} />
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}

const stopName = (ref: LegRef, which: "boardStop" | "alightStop") =>
  ref.leg?.[which]?.name ?? (which === "boardStop" ? "승차 정류장" : "하차 정류장");

function label(event: TimelineEvent): string {
  switch (event.kind) {
    case "LEFT_HOME":
      return "집 나섬";
    case "ARRIVED_STOP":
      return `${stopName(event.leg, "boardStop")} 도착 · ${transitLegLabel(event.leg.leg, event.leg.id)}`;
    case "NO_ATTEMPT":
      return `${transitLegLabel(event.leg.leg, event.leg.id)} · 탑승 기록 없음`;
    case "MISSED":
      return `${event.attempt.attemptSeq}번째 차 놓침`;
    case "DEPARTED":
      return `${event.attempt.attemptSeq}번째 차 탐 · 출발`;
    case "UNKNOWN":
      return `${event.attempt.attemptSeq}번째 차 (결과 모름)`;
    case "ALIGHTED":
      return `${stopName(event.leg, "alightStop")} 하차`;
    case "ARRIVED_DESTINATION":
      return "도착";
  }
}

/** 시도마다 예측 스냅샷과, 실제 출발이 있으면 그 차이(실제 − 예측)를 같이 보여 준다. */
function Prediction({ attempt }: { attempt: BoardingAttempt }) {
  const predicted = attempt.vehicleScheduledOrPredictedAt;
  if (!predicted) return <div className="muted small">예측 없음</div>;
  const error = predictionErrorSec(attempt);
  return (
    <div className="muted small">
      예측 {formatKstTime(predicted)}
      {error != null && <> · 실제 − 예측 {formatSignedSec(error)}</>}
    </div>
  );
}
