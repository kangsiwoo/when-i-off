import { lazy, Suspense } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import {
  useCommuteRoute,
  useRecommendationEvaluations,
  useRecommendationHistory,
} from "../api/queries";
import { addDays, formatKstTime, kstDate, kstToday } from "../kst";
import {
  buildTableRows,
  formatSignedMinutes,
  type Arrival,
  type TableRow,
} from "../recommendations/history";
import { EvaluationSummary } from "./EvaluationSummary";
import { RouteTabs } from "./RouteTabs";
import { ErrorMessage, Loading } from "./Status";

// Recharts는 이 탭에서만 쓰므로 따로 읽어 첫 화면 번들에 넣지 않는다.
const RecommendationCharts = lazy(() => import("./RecommendationCharts"));

const PRESETS = [7, 30, 90] as const;
const DEFAULT_DAYS = 30;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

export function RouteRecommendationsPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <p className="error">잘못된 경로 번호입니다.</p>;
  }
  return <RouteRecommendations id={id} />;
}

/** 기간은 주소(`?from=&to=`)에 둔다. 없으면 오늘(KST)까지 최근 30일. */
function useRange(): [{ from: string; to: string }, (from: string, to: string) => void] {
  const [params, setParams] = useSearchParams();
  const today = kstToday();
  const to = DATE.test(params.get("to") ?? "") ? params.get("to")! : today;
  const from = DATE.test(params.get("from") ?? "")
    ? params.get("from")!
    : addDays(to, -(DEFAULT_DAYS - 1));
  const set = (nextFrom: string, nextTo: string) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set("from", nextFrom);
        next.set("to", nextTo);
        return next;
      },
      { replace: true },
    );
  return [{ from, to }, set];
}

function RouteRecommendations({ id }: { id: number }) {
  const route = useCommuteRoute(id);
  const [{ from, to }, setRange] = useRange();
  const valid = DATE.test(from) && DATE.test(to) && from <= to;
  const history = useRecommendationHistory(id, from, to, valid);
  const evaluations = useRecommendationEvaluations(id, from, to, valid);
  const today = kstToday();

  return (
    <section className="viz">
      <p>
        <Link to="/">← 경로 목록</Link>
      </p>
      <h1>{route.data?.route.name ?? `경로 #${id}`}</h1>
      <RouteTabs id={id} />

      <form className="filters" onSubmit={(e) => e.preventDefault()} aria-label="기간">
        <div className="presets" role="group" aria-label="최근 기간">
          {PRESETS.map((n) => (
            <button
              key={n}
              type="button"
              aria-pressed={to === today && from === addDays(today, -(n - 1))}
              onClick={() => setRange(addDays(today, -(n - 1)), today)}
            >
              최근 {n}일
            </button>
          ))}
        </div>
        <label>
          시작일
          <input
            type="date"
            value={from}
            max={to}
            onChange={(e) => e.target.value && setRange(e.target.value, to)}
          />
        </label>
        <label>
          종료일
          <input
            type="date"
            value={to}
            min={from}
            onChange={(e) => e.target.value && setRange(from, e.target.value)}
          />
        </label>
      </form>

      {!valid ? (
        <p role="alert" className="error">
          종료일이 시작일보다 앞설 수 없습니다.
        </p>
      ) : route.error ? (
        <ErrorMessage error={route.error} />
      ) : history.isPending ? (
        <Loading />
      ) : history.error ? (
        <ErrorMessage error={history.error} />
      ) : history.data.length === 0 ? (
        <p className="empty">
          이 기간({from} ~ {to})에는 추천도 이동 기록도 없습니다. 기간을 넓혀 보세요.
        </p>
      ) : (
        // 기간을 바꾸는 동안 앞 결과를 흐리게 들고 있는다 (레이아웃이 뛰지 않게).
        <div style={{ opacity: history.isPlaceholderData ? 0.5 : 1 }}>
          {evaluations.isPending ? (
            <Loading />
          ) : evaluations.error ? (
            <ErrorMessage error={evaluations.error} />
          ) : (
            <EvaluationSummary data={evaluations.data} days={history.data} from={from} to={to} />
          )}
          <Suspense fallback={<Loading />}>
            <RecommendationCharts days={history.data} />
          </Suspense>
          <h2>날짜별 비교</h2>
          <p className="muted small">
            시각은 모두 KST. 실제 출발의 차이는 추천 대비(+면 늦게 나섬), 실제 도착의 차이는 목표
            대비(+면 늦게 도착)입니다.
          </p>
          <div className="table-scroll">
            <HistoryTable rows={buildTableRows(history.data)} />
          </div>
        </div>
      )}
    </section>
  );
}

/** 시각을 KST로. 날짜가 기준일과 다르면 날짜도 붙인다 (자정을 넘긴 경우). */
function kstClock(iso: string, date: string): string {
  const hhmm = formatKstTime(iso).slice(0, 5);
  const d = kstDate(iso);
  return d === date ? hhmm : `${hhmm} (${Number(d.slice(5, 7))}/${Number(d.slice(8, 10))})`;
}

function HistoryTable({ rows }: { rows: TableRow[] }) {
  return (
    <table className="history-table" aria-label="날짜별 추천과 실제">
      <thead>
        <tr>
          <th>날짜</th>
          <th>추천 출발</th>
          <th>실제 출발 (±분)</th>
          <th>목표 도착</th>
          <th>실제 도착 (지각 여부)</th>
          <th>결과</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <HistoryRow key={row.key} row={row} />
        ))}
      </tbody>
    </table>
  );
}

function HistoryRow({ row }: { row: TableRow }) {
  const { date, trip, recommendations, leaveDiffs, targetArrivalAt, arrival } = row;
  const first = row.tripIndex === 0;
  const showVersion = recommendations.length > 1;
  return (
    <tr className={first ? undefined : "same-day"}>
      <td className="day-cell">{first ? date : ""}</td>
      <td>
        {!first ? null : recommendations.length === 0 ? (
          <span className="muted">추천 없음</span>
        ) : (
          <span className="stack">
            {recommendations.map((r) => (
              <span key={r.modelVersion}>
                <span className="ver">{r.modelVersion}</span>
                {kstClock(r.recommendedLeaveHomeAt, date)}{" "}
                <span className="muted small">
                  ({Math.round(r.catchProbability * 100)}%, 여유 {Math.round(r.bufferSeconds / 60)}
                  분{r.minTransitSampleCount != null && `, 표본 ${r.minTransitSampleCount}`})
                </span>
              </span>
            ))}
          </span>
        )}
      </td>
      <td>
        {!trip ? (
          <span className="muted">기록 없음</span>
        ) : !trip.leftHomeAt ? (
          <span className="muted">—</span>
        ) : (
          <span className="stack">
            <span>{kstClock(trip.leftHomeAt, date)}</span>
            {leaveDiffs.map((d) =>
              d.sec === null ? null : (
                <span key={d.modelVersion} className="small">
                  {showVersion && <span className="ver">{d.modelVersion}</span>}
                  {formatSignedMinutes(d.sec)}
                </span>
              ),
            )}
          </span>
        )}
      </td>
      <td>
        {targetArrivalAt ? kstClock(targetArrivalAt, date) : <span className="muted">—</span>}
      </td>
      <td>
        {!trip ? (
          <span className="muted">—</span>
        ) : !trip.arrivedDestinationAt ? (
          <span className="muted">도착 기록 없음</span>
        ) : (
          <span className="stack">
            <span>{kstClock(trip.arrivedDestinationAt, date)}</span>
            {arrival && <Lateness arrival={arrival} />}
          </span>
        )}
      </td>
      <td>
        {!trip ? (
          <span className="muted">—</span>
        ) : (
          <span>
            {trip.allLegsCaught ? (
              <span className="badge badge-caught">전 구간 탑승</span>
            ) : (
              <span className="badge badge-incomplete">탑승 못 한 구간 있음</span>
            )}
            {trip.missedCount > 0 && <span className="missed"> 놓친 차 {trip.missedCount}대</span>}
          </span>
        )}
      </td>
    </tr>
  );
}

/** 지각 여부. 상태 색은 아이콘에만, 뜻은 글자로도 쓴다. */
function Lateness({ arrival }: { arrival: Arrival }) {
  return arrival.late ? (
    <span className="status">
      <span className="status-icon" style={{ color: "var(--status-critical)" }} aria-hidden>
        ▲
      </span>
      지각 {formatSignedMinutes(arrival.diffSec)}
    </span>
  ) : (
    <span className="status">
      <span className="status-icon" style={{ color: "var(--status-good)" }} aria-hidden>
        ●
      </span>
      제시간 {formatSignedMinutes(arrival.diffSec)}
    </span>
  );
}
