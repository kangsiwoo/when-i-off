import type { ReactNode } from "react";
import { Link } from "react-router";
import type { CommuteRoute, CommuteTrip, RouteLeg } from "../api/client";
import { formatDuration } from "../format";
import { formatKstTime } from "../kst";
import { formatSignedMinutes } from "../recommendations/history";
import {
  buildTicket,
  formatProbability,
  STAMP_LABEL,
  ticketDate,
  ticketNo,
  type TicketSegment,
} from "../trips/ticket";
import { summarizeTrip } from "../trips/timeline";
import { TripResult } from "./TripResult";

/**
 * 승차권(#96): 출근·퇴근 한 번 = 한 장. 왼쪽 본권(방향·날짜·번호, 출발 → 도착, 구간 띠)과 절취선 너머의
 * 보관권(추천/실제 출발, 목표 대비 도착, 도장). 색·글꼴은 디자인 토큰이고 모양은 전부 CSS(index.css `.ticket`)다.
 * 추천은 trip 응답의 `recommendation`(#98)이라 API 호출이 늘지 않는다. 기록에 없는 값은 "—"로 둔다.
 */
export function TripTicket({
  trip,
  route,
  legs,
  routeName,
  href,
  compact = false,
  cancelled = false,
}: {
  trip: CommuteTrip;
  route?: CommuteRoute;
  legs?: RouteLeg[];
  routeName?: string;
  /** 있으면 날짜가 상세로 가는 링크가 된다 (목록) */
  href?: string;
  compact?: boolean;
  /** 서버는 취소한 trip을 지우므로 화면이 넘길 때만 (#88) */
  cancelled?: boolean;
}) {
  const t = buildTicket(trip, route, legs, cancelled);
  const summary = summarizeTrip(trip, legs);
  const date = ticketDate(trip.tripDate);
  const name = routeName ?? route?.name ?? `경로 #${trip.routeId}`;
  return (
    <article
      className={`ticket${compact ? " ticket-compact" : ""} ticket-${t.stamp.toLowerCase()}`}
      aria-label={`승차권 ${trip.tripDate} ${t.direction}`}
    >
      <div className="ticket-main">
        <header className="ticket-head">
          <span className="ticket-kind">{t.direction} 승차권</span>
          <span className="ticket-date">
            {href ? <Link to={href}>{trip.tripDate}</Link> : date}
          </span>
          <span className="ticket-no">{ticketNo(trip.id)}</span>
        </header>
        <p className="ticket-name">{name}</p>
        <div className="ticket-route">
          <Endpoint label="출발" place={t.from} at={t.leftHomeAt} />
          <div className="ticket-via" aria-hidden="true">
            <span className="ticket-arrow">→</span>
          </div>
          <Endpoint label="도착" place={t.to} at={t.arrivedAt} />
          <p className="ticket-total">
            <span>소요</span> {formatDuration(summary.totalSec)}
          </p>
        </div>
        {t.segments.length > 0 && (
          <ol className="ticket-legs" aria-label="구간">
            {t.segments.map((s) => (
              <Segment key={s.key} segment={s} compact={compact} />
            ))}
          </ol>
        )}
        <div className="ticket-result">
          <TripResult summary={summary} />
        </div>
      </div>
      <div className="ticket-stub">
        <dl className="ticket-facts">
          <Fact label="추천 출발">
            {t.recommendedLeaveAt ? hm(t.recommendedLeaveAt) : "—"}
            {t.catchProbability !== null && (
              <small title="추천대로 나서면 놓치지 않을 확률">
                {" "}
                {formatProbability(t.catchProbability)}
              </small>
            )}
          </Fact>
          <Fact label="실제 출발">
            {t.leftHomeAt ? hm(t.leftHomeAt) : "—"}
            {t.leaveDiffSec !== null && <small> {formatSignedMinutes(t.leaveDiffSec)}</small>}
          </Fact>
          <Fact label="목표 도착">
            {t.targetAt ? (
              <span
                title={
                  t.targetSource === "recommendation"
                    ? "그날 추천의 목표 도착"
                    : "경로의 기본 목표 도착"
                }
              >
                {hm(t.targetAt)}
              </span>
            ) : (
              "—"
            )}
          </Fact>
          <Fact label="목표 대비">
            {t.arrivalDiffSec === null ? "—" : formatSignedMinutes(t.arrivalDiffSec)}
          </Fact>
        </dl>
        <p className={`ticket-stamp stamp-${t.stamp.toLowerCase()}`}>{STAMP_LABEL[t.stamp]}</p>
      </div>
    </article>
  );
}

const hm = (iso: string) => formatKstTime(iso).slice(0, 5);

function Endpoint({ label, place, at }: { label: string; place: string; at: string | null }) {
  return (
    <div className="ticket-end">
      <span className="ticket-label">{label}</span>
      <strong className="ticket-place">{place}</strong>
      <span className="ticket-time">
        {at ? (
          <>
            {hm(at)}
            <small>{formatKstTime(at).slice(5)}</small>
          </>
        ) : (
          "—"
        )}
      </span>
    </div>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function Segment({ segment: s, compact }: { segment: TicketSegment; compact: boolean }) {
  if (s.kind === "walk") {
    return (
      <li className="ticket-leg ticket-leg-walk">
        <span className="ticket-bar" aria-hidden="true" />
        <span className="ticket-leg-name">도보</span>
        {!compact && <span className="ticket-leg-meta">{s.distance ?? "—"}</span>}
      </li>
    );
  }
  const clock = (iso: string | null) => (iso ? hm(iso) : "—");
  return (
    <>
      {s.missed.map((m) => (
        <li
          key={`${s.key}-m${m.attemptSeq}`}
          className={`ticket-leg ticket-leg-void tone-${s.tone}`}
        >
          <span className="ticket-bar" aria-hidden="true" />
          <span className="ticket-leg-name">
            <s>{compact ? `${m.attemptSeq}번째` : `${m.attemptSeq}번째 차`}</s> 놓침
          </span>
          {!compact && <span className="ticket-leg-meta">{clock(m.at)} 출발</span>}
        </li>
      ))}
      <li className={`ticket-leg ticket-leg-transit tone-${s.tone} leg-${s.status}`}>
        <span className="ticket-bar" aria-hidden="true" />
        <span className="ticket-leg-name">{s.line}</span>
        {!compact && (
          <span className="ticket-leg-meta">
            {s.board ?? "?"} {clock(s.departedAt)} → {s.alight ?? "?"} {clock(s.alightedAt)}
            {s.status === "none" && " · 탑승 기록 없음"}
            {s.status === "unknown" && " · 결과 모름"}
          </span>
        )}
      </li>
    </>
  );
}
