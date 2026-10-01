import { useState, type FormEvent } from "react";
import type { TransitLine, TransitMode } from "../api/client";
import { useTransitLineSearch } from "../api/queries";
import { modeLabel } from "../format";

const MODES: TransitMode[] = ["BUS", "SUBWAY", "GTX"];

/** `/transit-lines/search`로 노선을 찾아 고른다. 구간 편집(#64)과 시간표 화면(#70)이 같이 쓴다. */
export function LinePicker({
  line,
  onLine,
  keyPrefix,
  emptyLabel = "미선택",
}: {
  line: TransitLine | null;
  onLine: (line: TransitLine) => void;
  keyPrefix: string;
  /** 고른 노선 객체가 없을 때 보일 말 (시간표 화면은 id만 아는 노선을 `노선 #1`로 보인다). */
  emptyLabel?: string;
}) {
  const [input, setInput] = useState("");
  const [mode, setMode] = useState<TransitMode | "">("");
  const [submitted, setSubmitted] = useState("");
  const search = useTransitLineSearch(submitted, mode || undefined);
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(input.trim());
  };
  return (
    <div className="line-picker">
      <p>
        <span className="stop-label">노선</span>{" "}
        {line ? (
          `${line.name} (${modeLabel(line.mode)})`
        ) : (
          <span className="muted">{emptyLabel}</span>
        )}
      </p>
      {/* 바깥 폼 안에 폼을 두지 않도록 div + Enter 처리 */}
      <div className="form" role="search">
        <div className="field">
          <label htmlFor={`mode-${keyPrefix}`}>수단</label>
          <select
            id={`mode-${keyPrefix}`}
            value={mode}
            onChange={(e) => setMode(e.target.value as TransitMode | "")}
          >
            <option value="">전체</option>
            {MODES.map((m) => (
              <option key={m} value={m}>
                {modeLabel(m)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`kw-${keyPrefix}`}>노선 검색</label>
          <input
            id={`kw-${keyPrefix}`}
            value={input}
            placeholder="M4403, GTX-A"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && onSubmit(e)}
          />
        </div>
        <button type="button" className="secondary" onClick={onSubmit}>
          검색
        </button>
      </div>
      {search.isFetching && <p className="muted small">검색 중…</p>}
      {search.error && <p className="error small">검색하지 못했습니다: {search.error.message}</p>}
      {search.data &&
        (search.data.length === 0 ? (
          <p className="muted small">“{submitted}” 노선이 없습니다.</p>
        ) : (
          <ul className="candidates" aria-label="노선 검색 결과">
            {search.data.map((l) => (
              <li key={l.id}>
                <button
                  type="button"
                  className="link-button"
                  onClick={() => {
                    onLine(l);
                    setSubmitted("");
                  }}
                >
                  {l.name}
                </button>{" "}
                <span className="muted small">
                  {modeLabel(l.mode)}
                  {l.hasRealtimeApi ? "" : " · 시간표만"}
                </span>
              </li>
            ))}
          </ul>
        ))}
    </div>
  );
}
