import { useId, useState, type FormEvent } from "react";
import type { NextDepartures } from "../api/client";
import { useNextDepartures, type NextDeparturesQuery } from "../api/queries";
import { dayTypeLabel } from "../format";
import { formatKstTime, kstDate } from "../kst";
import type { ScheduleRow } from "../schedules/csv";
import {
  directionCandidates,
  formatWait,
  lineName,
  MAX_LIMIT,
  minutesUntil,
  nextQuery,
  nowKstInput,
  stopCandidates,
  stopName,
  type KnownTransit,
  type NextDraft,
} from "../schedules/next";
import { KstInput } from "./KstInput";
import { LinePicker } from "./LinePicker";
import { ErrorMessage } from "./Status";

/**
 * 업로드 확인용 "다음 출발" (#70): `GET /transit-lines/{id}/schedules/next`. 노선은 검색으로, 정류장은 id(방금 고른
 * CSV와 내 경로 구간에서 후보)로, 방향은 코드 그대로 고른다. 기준 시각은 KST로 넣는다.
 */
export function NextDeparturesChecker({
  draft,
  onDraft,
  rows,
  known,
}: {
  draft: NextDraft;
  onDraft: (d: NextDraft) => void;
  rows: ScheduleRow[];
  known: KnownTransit;
}) {
  const ids = { stop: useId(), direction: useId(), limit: useId(), directions: useId() };
  const [problem, setProblem] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<NextDeparturesQuery | null>(null);
  const next = useNextDepartures(submitted);

  const change = (d: Partial<NextDraft>) => {
    setProblem(null);
    onDraft({ ...draft, ...d });
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const r = nextQuery(draft);
    if (r.problem !== undefined) return setProblem(r.problem);
    setProblem(null);
    setSubmitted(r.query);
    // 같은 조건으로 다시 누르면 업로드 뒤 새 값을 받는다.
    if (JSON.stringify(r.query) === JSON.stringify(submitted)) void next.refetch();
  };

  const stops = stopCandidates(draft.lineId, rows, known);
  const directions = directionCandidates(draft.lineId, draft.stopId, rows);

  return (
    <section className="card next-departures" aria-label="다음 출발">
      <h2>다음 출발</h2>
      <p className="muted small">
        정적 시간표 기준으로 기준 시각 이후(같은 시각 포함) 출발하는 차편. 오늘 남은 차편이 모자라면
        다음 날 첫차부터 이어지고, 날마다 day_type(평일/토요일/일요일·공휴일)을 다시 판정합니다.
      </p>
      <LinePicker
        line={draft.line}
        onLine={(l) => change({ lineId: l.id, line: l })}
        keyPrefix="next"
        emptyLabel={draft.lineId != null ? lineName(draft.lineId, known) : "미선택"}
      />
      <form className="form next-form" onSubmit={submit} aria-label="다음 출발 조회">
        <div className="field">
          <label htmlFor={ids.stop}>정류장 id</label>
          <input
            id={ids.stop}
            inputMode="numeric"
            value={draft.stopId}
            size={8}
            onChange={(e) => change({ stopId: e.target.value })}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.direction}>방향 코드</label>
          <input
            id={ids.direction}
            value={draft.direction}
            list={ids.directions}
            size={6}
            placeholder="UP"
            onChange={(e) => change({ direction: e.target.value })}
          />
          <datalist id={ids.directions}>
            {directions.map((d) => (
              <option key={d} value={d} />
            ))}
          </datalist>
        </div>
        <KstInput label="기준 시각" value={draft.at} onChange={(at) => change({ at })} />
        <button type="button" className="secondary" onClick={() => change({ at: nowKstInput() })}>
          지금
        </button>
        <div className="field">
          <label htmlFor={ids.limit}>대수</label>
          <input
            id={ids.limit}
            type="number"
            min={1}
            max={MAX_LIMIT}
            value={draft.limit}
            onChange={(e) => change({ limit: e.target.value })}
            className="narrow"
          />
        </div>
        <button type="submit">조회</button>
        {stops.length > 0 && (
          <p className="form-note small">
            <span className="muted">정류장 후보</span>{" "}
            {stops.map((id) => (
              <button
                key={id}
                type="button"
                className="chip"
                aria-pressed={draft.stopId.trim() === String(id)}
                onClick={() => change({ stopId: String(id) })}
              >
                {stopName(id, known)}
              </button>
            ))}
          </p>
        )}
        {directions.length > 0 && (
          <p className="form-note small">
            <span className="muted">이 파일의 방향</span>{" "}
            {directions.map((d) => (
              <button
                key={d}
                type="button"
                className="chip"
                aria-pressed={draft.direction.trim() === d}
                onClick={() => change({ direction: d })}
              >
                {d}
              </button>
            ))}
          </p>
        )}
        {problem && (
          <p role="alert" className="error">
            {problem}
          </p>
        )}
      </form>
      {submitted && next.isPending && <p className="muted">불러오는 중…</p>}
      {next.error && <ErrorMessage error={next.error} />}
      {next.data && submitted && (
        <DepartureTable data={next.data} query={submitted} known={known} />
      )}
    </section>
  );
}

function DepartureTable({
  data,
  query,
  known,
}: {
  data: NextDepartures;
  query: NextDeparturesQuery;
  known: KnownTransit;
}) {
  const baseDate = kstDate(query.at);
  return (
    <div className="departures">
      <p className="small">
        <strong>{lineName(data.transitLineId, known)}</strong> · {stopName(data.stopId, known)} ·
        방향 <code>{data.directionCode}</code> · 기준 {kstDate(query.at)} {formatKstTime(query.at)}{" "}
        (KST)
      </p>
      {data.departures.length === 0 ? (
        <p className="empty">
          기준 시각부터 다음 날까지 이 노선·정류장·방향의 시간표가 없습니다. 방향 코드나 day_type을
          확인하세요.
        </p>
      ) : (
        <div className="table-scroll">
          <table aria-label="다음 출발 목록">
            <thead>
              <tr>
                <th>#</th>
                <th>출발 (KST)</th>
                <th>운행일</th>
                <th>시간표</th>
                <th>기준 시각부터</th>
              </tr>
            </thead>
            <tbody>
              {data.departures.map((d, i) => (
                <tr key={d.departureAt}>
                  <td className="muted">{i + 1}</td>
                  <td>
                    <strong>{formatKstTime(d.departureAt)}</strong>
                    {d.serviceDate !== baseDate && <span className="badge badge-low">다음 날</span>}
                  </td>
                  <td>{d.serviceDate}</td>
                  <td>{dayTypeLabel(d.dayType)}</td>
                  <td>{formatWait(minutesUntil(query.at, d.departureAt))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
