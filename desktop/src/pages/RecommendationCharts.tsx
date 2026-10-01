import type { ReactNode } from "react";
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
} from "recharts";
import type { RecommendationHistoryDay } from "../api/client";
import {
  actualKey,
  bufKey,
  buildChartSeries,
  formatMinuteOfDay,
  lastActualPoint,
  labelableEnds,
  lastIndexWithValue,
  recKey,
  seriesEnds,
  shortDate,
  versionSlots,
  type ChartRow,
  type ChartSeries,
  type SeriesSlot,
} from "../recommendations/history";

// 두 차트(#62). 출발 시각(하루 중 시각)과 buffer(분)는 단위가 달라 한 차트에 축 두 개로 겹치지 않고 따로 그린다.
// 날짜 축은 둘이 같고, 버전 색도 같다. 이 파일은 Recharts를 끌고 오므로 페이지가 lazy로 읽는다.

const color = (slot: SeriesSlot) => `var(--${slot === "other" ? "series-other" : slot})`;

const HEIGHT = 260; // 축 라벨 띠를 포함한 높이
const MARGIN = { top: 12, right: 56, bottom: 4, left: 4 };
const AXIS_TICK = { fill: "var(--viz-muted)", fontSize: 12 };

export default function RecommendationCharts({ days }: { days: RecommendationHistoryDay[] }) {
  const series = buildChartSeries(days);
  if (!series) return null;
  const slots = versionSlots(series.versions);
  return (
    <>
      <DepartureChart series={series} slots={slots} />
      <BufferChart series={series} slots={slots} />
    </>
  );
}

interface ChartProps {
  series: ChartSeries;
  slots: Map<string, SeriesSlot>;
}

function DepartureChart({ series, slots }: ChartProps) {
  const { rows, versions, maxTrips } = series;
  const hasRecommendations = versions.length > 0;
  const lastActual = lastActualPoint(series);
  const labeled = labelableEnds(
    [
      ...seriesEnds(
        rows,
        versions.map((_, i) => recKey(i)),
      ),
      ...(lastActual ? seriesEnds(rows, [lastActual.key]) : []),
    ],
    series.timeDomain,
  );
  return (
    <figure className="viz-card" aria-labelledby="departure-chart-title">
      <h2 id="departure-chart-title">추천 출발 vs 실제 출발</h2>
      <p className="viz-sub">
        날짜별 집 나서는 시각(KST). 추천은 모델 버전마다 그날 마지막으로 계산된 값이다.
      </p>
      <ul className="viz-legend" aria-label="범례">
        {maxTrips > 0 && (
          <li>
            <span className="viz-key-dot" style={{ background: color("series-1") }} />
            실제 출발
          </li>
        )}
        {versions.map((v) => (
          <li key={v}>
            <span className="viz-key-line" style={{ background: color(slots.get(v)!) }} />
            추천 {v}
          </li>
        ))}
      </ul>
      <ResponsiveContainer width="100%" height={HEIGHT}>
        <LineChart data={rows} margin={MARGIN} accessibilityLayer>
          <CartesianGrid vertical={false} stroke="var(--viz-grid)" />
          <XAxis
            dataKey="date"
            tickFormatter={shortDate}
            tick={AXIS_TICK}
            stroke="var(--viz-axis)"
            tickLine={false}
            minTickGap={16}
          />
          <YAxis
            type="number"
            domain={series.timeDomain}
            ticks={series.timeTicks}
            tickFormatter={formatMinuteOfDay}
            tick={AXIS_TICK}
            axisLine={false}
            tickLine={false}
            width={48}
            allowDataOverflow
          />
          <Tooltip
            cursor={{ stroke: "var(--viz-axis)", strokeWidth: 1 }}
            content={(props) => (
              <DepartureTooltip
                active={props.active}
                payload={props.payload}
                series={series}
                slots={slots}
              />
            )}
            isAnimationActive={false}
          />
          {versions.map((v, i) => (
            <Line
              key={v}
              dataKey={recKey(i)}
              name={`추천 ${v}`}
              stroke={color(slots.get(v)!)}
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              dot={{ r: 3, strokeWidth: 0, fill: color(slots.get(v)!) }}
              activeDot={{ r: 5, stroke: "var(--viz-surface)", strokeWidth: 2 }}
              connectNulls
              isAnimationActive={false}
              label={labeled.has(recKey(i)) ? endLabel(rows, recKey(i), `추천 ${v}`) : undefined}
            />
          ))}
          {Array.from({ length: maxTrips }, (_, i) => (
            <Line
              key={actualKey(i)}
              dataKey={actualKey(i)}
              name="실제 출발"
              stroke="none"
              legendType="circle"
              // 실제 출발은 선 없이 점만: 하루에 여러 번일 수 있고, 추천(선)과 모양으로도 구분된다.
              dot={{ r: 4, fill: color("series-1"), stroke: "var(--viz-surface)", strokeWidth: 2 }}
              activeDot={{
                r: 6,
                fill: color("series-1"),
                stroke: "var(--viz-surface)",
                strokeWidth: 2,
              }}
              isAnimationActive={false}
              label={
                lastActual?.key === actualKey(i) && labeled.has(actualKey(i))
                  ? endLabel(rows, actualKey(i), "실제", lastActual.index)
                  : undefined
              }
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
      {!hasRecommendations && (
        <p className="muted small viz-empty-chart">이 기간에는 추천이 없어 실제 출발만 보입니다.</p>
      )}
    </figure>
  );
}

function BufferChart({ series, slots }: ChartProps) {
  const { rows, versions } = series;
  const labeled = labelableEnds(
    seriesEnds(
      rows,
      versions.map((_, i) => bufKey(i)),
    ),
    series.bufferDomain,
  );
  return (
    <figure className="viz-card" aria-labelledby="buffer-chart-title">
      <h2 id="buffer-chart-title">buffer 추이</h2>
      <p className="viz-sub">
        추천 출발에 더한 여유 시간(분). 기록이 쌓여 예측이 좁아지면 줄어든다.
      </p>
      {versions.length === 0 ? (
        <p className="muted small viz-empty-chart">이 기간에는 추천이 없습니다.</p>
      ) : (
        <>
          {versions.length > 1 && (
            <ul className="viz-legend" aria-label="범례">
              {versions.map((v) => (
                <li key={v}>
                  <span className="viz-key-line" style={{ background: color(slots.get(v)!) }} />
                  {v}
                </li>
              ))}
            </ul>
          )}
          <ResponsiveContainer width="100%" height={HEIGHT - 40}>
            <LineChart data={rows} margin={MARGIN} accessibilityLayer>
              <CartesianGrid vertical={false} stroke="var(--viz-grid)" />
              <XAxis
                dataKey="date"
                tickFormatter={shortDate}
                tick={AXIS_TICK}
                stroke="var(--viz-axis)"
                tickLine={false}
                minTickGap={16}
              />
              <YAxis
                type="number"
                domain={series.bufferDomain}
                ticks={series.bufferTicks}
                tickFormatter={(m: number) => `${m}분`}
                tick={AXIS_TICK}
                axisLine={false}
                tickLine={false}
                width={48}
              />
              <Tooltip
                cursor={{ stroke: "var(--viz-axis)", strokeWidth: 1 }}
                content={(props) => (
                  <BufferTooltip
                    active={props.active}
                    payload={props.payload}
                    series={series}
                    slots={slots}
                  />
                )}
                isAnimationActive={false}
              />
              {versions.map((v, i) => (
                <Line
                  key={v}
                  dataKey={bufKey(i)}
                  name={v}
                  stroke={color(slots.get(v)!)}
                  strokeWidth={2}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  dot={{ r: 3, strokeWidth: 0, fill: color(slots.get(v)!) }}
                  activeDot={{ r: 5, stroke: "var(--viz-surface)", strokeWidth: 2 }}
                  connectNulls
                  isAnimationActive={false}
                  label={labeled.has(bufKey(i)) ? endLabel(rows, bufKey(i), v) : undefined}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </>
      )}
    </figure>
  );
}

/** 계열의 마지막 점 오른쪽에 이름 한 번만 (점마다 숫자를 달지 않는다). 글자는 본문 잉크색이다. */
function endLabel(rows: ChartRow[], key: string, text: string, at?: number) {
  const last = at ?? lastIndexWithValue(rows, key);
  return function EndLabel(props: { x?: number | string; y?: number | string; index?: number }) {
    if (props.index !== last || props.x == null || props.y == null) return null;
    return (
      <text
        x={Number(props.x) + 8}
        y={Number(props.y)}
        dy="0.32em"
        fontSize={12}
        fill="var(--viz-ink-2)"
      >
        {text}
      </text>
    );
  };
}

/** Recharts가 넘기는 것 중 쓰는 것만. 줄 전체(`payload[0].payload`)에서 모든 계열을 읽는다. */
type TooltipProps = Pick<TooltipContentProps<number, string>, "active" | "payload"> & ChartProps;

function TooltipFrame({ date, children }: { date: string; children: ReactNode }) {
  return (
    <div className="viz-tooltip">
      <div className="viz-tooltip-date">{date}</div>
      <ul>{children}</ul>
    </div>
  );
}

function TooltipRow({ swatch, value, label }: { swatch: ReactNode; value: string; label: string }) {
  return (
    <li>
      {swatch}
      <strong>{value}</strong>
      <span className="viz-tooltip-label">{label}</span>
    </li>
  );
}

const lineKey = (slot: SeriesSlot) => (
  <span className="viz-key-line" style={{ background: color(slot), width: 12 }} />
);

/** 크로스헤어가 가리킨 날의 모든 계열 — 추천은 버전마다, 실제는 그날 trip마다. */
function DepartureTooltip({ active, payload, series, slots }: TooltipProps) {
  const row = payload?.[0]?.payload as ChartRow | undefined;
  if (!active || !row || !row.hasData) return null;
  const items: ReactNode[] = [];
  series.versions.forEach((v, i) => {
    const t = row[recKey(i)];
    if (typeof t === "number") {
      items.push(
        <TooltipRow
          key={`r${i}`}
          swatch={lineKey(slots.get(v)!)}
          value={formatMinuteOfDay(t)}
          label={`추천 ${v}`}
        />,
      );
    }
  });
  for (let i = 0; i < series.maxTrips; i++) {
    const t = row[actualKey(i)];
    if (typeof t === "number") {
      items.push(
        <TooltipRow
          key={`a${i}`}
          swatch={<span className="viz-key-dot" style={{ background: color("series-1") }} />}
          value={formatMinuteOfDay(t)}
          label={series.maxTrips > 1 ? `실제 출발 ${i + 1}` : "실제 출발"}
        />,
      );
    }
  }
  if (items.length === 0)
    items.push(
      <li key="none" className="muted">
        출발 기록 없음
      </li>,
    );
  return <TooltipFrame date={row.date}>{items}</TooltipFrame>;
}

function BufferTooltip({ active, payload, series, slots }: TooltipProps) {
  const row = payload?.[0]?.payload as ChartRow | undefined;
  if (!active || !row || !row.hasData) return null;
  const items = series.versions.flatMap((v, i) => {
    const b = row[bufKey(i)];
    return typeof b === "number"
      ? [
          <TooltipRow
            key={v}
            swatch={lineKey(slots.get(v)!)}
            value={`${Math.round(b)}분`}
            label={v}
          />,
        ]
      : [];
  });
  if (items.length === 0) return null;
  return <TooltipFrame date={row.date}>{items}</TooltipFrame>;
}
