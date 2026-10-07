import { Link, useParams } from "react-router";
import type {
  LegCalibration,
  PredictionCalibrationRow,
  TravelTimeCalibrationRow,
  WalkingProfile,
} from "../api/client";
import { useCommuteRoute, useRouteCalibration } from "../api/queries";
import {
  isLowSample,
  predictionFallback,
  resolveWalkingSpeed,
  travelTimeFallback,
  type Resolved,
  type Source,
} from "../calibration/chain";
import {
  dayTypeLabel,
  formatDistance,
  formatKst,
  formatSecSpread,
  formatSpeed,
  modeLabel,
} from "../format";
import { lineTone } from "../map/lineTone";
import { RouteTabs } from "./RouteTabs";
import { ErrorMessage, Loading } from "./Status";

const EMPTY_TEXT = "아직 기록이 없어 기본값으로 추천합니다";

const WALK_SOURCE: Record<Source, string> = {
  calibrated: "이 구간 기록",
  inherited: "전체 도보 기록",
  default: "기본값",
};

const TRANSIT_SOURCE: Record<Source, string> = {
  calibrated: "그 시간대 기록",
  inherited: "모든 시간대를 합친 값",
  default: "기본값",
};

export function RouteCalibrationPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <p className="error">잘못된 경로 번호입니다.</p>;
  }
  return <RouteCalibration id={id} />;
}

function RouteCalibration({ id }: { id: number }) {
  const route = useCommuteRoute(id);
  const calibration = useRouteCalibration(id);

  if (route.isPending || calibration.isPending) return <Loading />;
  if (route.error) return <ErrorMessage error={route.error} />;
  if (calibration.error) return <ErrorMessage error={calibration.error} />;

  const { minSamples, globalWalkingProfile, legs } = calibration.data;
  const transitLines = new Map(
    route.data.legs.map((leg) => [leg.id, leg.transitLine?.mode] as const),
  );
  return (
    <section>
      <p>
        <Link to="/">← 경로 목록</Link>
      </p>
      <h1>{route.data.route.name}</h1>
      <RouteTabs id={id} />
      <p className="muted">
        샘플이 {minSamples}개 미만인 값은 추천에 쓰이지 않고, 도보는 전체 도보 기록 → 기본값,
        대중교통은 모든 시간대를 합친 값 → 기본값으로 내려갑니다.
      </p>

      <h2>전체 도보 기록</h2>
      {globalWalkingProfile ? (
        <WalkingFacts profile={globalWalkingProfile} minSamples={minSamples} />
      ) : (
        <p className="muted">아직 없습니다. 구간 기록이 부족하면 기본값으로 내려갑니다.</p>
      )}

      {legs.length === 0 ? (
        <p className="muted">구간이 없습니다.</p>
      ) : (
        [...legs]
          .sort((a, b) => a.seqOrder - b.seqOrder)
          .map((leg) =>
            leg.legType === "WALK" ? (
              <WalkLeg
                key={leg.routeLegId}
                leg={leg}
                global={globalWalkingProfile}
                minSamples={minSamples}
              />
            ) : (
              <TransitLeg
                key={leg.routeLegId}
                leg={leg}
                mode={transitLines.get(leg.routeLegId)}
                minSamples={minSamples}
              />
            ),
          )
      )}
    </section>
  );
}

function LowBadge({ sampleCount, minSamples }: { sampleCount: number; minSamples: number }) {
  if (!isLowSample(sampleCount, minSamples)) return null;
  return <span className="badge badge-low">신뢰도 낮음</span>;
}

function WalkingFacts({ profile, minSamples }: { profile: WalkingProfile; minSamples: number }) {
  return (
    <dl className="facts">
      <dt>평균 속도</dt>
      <dd>{formatSpeed(profile.avgSpeedMps, profile.stddevSpeedMps)}</dd>
      <dt>샘플</dt>
      <dd>
        {profile.sampleCount}개{" "}
        <LowBadge sampleCount={profile.sampleCount} minSamples={minSamples} />
      </dd>
      <dt>갱신</dt>
      <dd>{formatKst(profile.updatedAt)}</dd>
    </dl>
  );
}

function UsedValue({ label, text }: { label: string; text: string }) {
  return (
    <p className="used-value">
      추천에 쓰이는 값: <strong>{text}</strong> <span className="muted">({label})</span>
    </p>
  );
}

function WalkLeg({
  leg,
  global,
  minSamples,
}: {
  leg: LegCalibration;
  global: WalkingProfile | null | undefined;
  minSamples: number;
}) {
  const used = resolveWalkingSpeed(leg.walkingProfile, global, minSamples);
  return (
    <article className="calibration-leg" aria-label={`구간 ${leg.seqOrder}`}>
      <h2>
        {leg.seqOrder}. <span className="badge badge-walk">도보</span>{" "}
        <span className="muted small">{formatDistance(leg.plannedDistanceM)}</span>
      </h2>
      {leg.walkingProfile ? (
        <WalkingFacts profile={leg.walkingProfile} minSamples={minSamples} />
      ) : (
        <p className="empty">{global ? "이 구간의 기록이 아직 없습니다." : EMPTY_TEXT}</p>
      )}
      <UsedValue
        label={WALK_SOURCE[used.source] + sampleNote(used)}
        text={formatSpeed(used.value.mean, used.value.stddev)}
      />
    </article>
  );
}

function sampleNote(used: Resolved): string {
  return used.source === "default" ? "" : `, 샘플 ${used.sampleCount}개`;
}

function TransitLeg({
  leg,
  mode,
  minSamples,
}: {
  leg: LegCalibration;
  mode: Parameters<typeof modeLabel>[0] | undefined;
  minSamples: number;
}) {
  const prediction = predictionFallback(leg.predictionRows, minSamples);
  const travel = travelTimeFallback(leg.travelTimeRows, minSamples, leg.plannedTravelSec);
  return (
    <article className="calibration-leg" aria-label={`구간 ${leg.seqOrder}`}>
      <h2>
        {leg.seqOrder}.{" "}
        <span
          className={`badge badge-transit line-${mode ? lineTone({ name: leg.transitLineName ?? "", mode }) : "ink"}`}
        >
          대중교통
        </span>{" "}
        {leg.transitLineName ?? "노선 ?"}
        {mode ? ` (${modeLabel(mode)})` : ""} {leg.boardStopName ?? "?"} →{" "}
        {leg.alightStopName ?? "?"}
      </h2>

      <h3>예측 오차 (실제 − 예측, {leg.boardStopName ?? "승차 정류장"})</h3>
      {leg.predictionRows.length === 0 ? (
        <p className="empty">{EMPTY_TEXT}</p>
      ) : (
        <BandTable
          caption="예측 오차"
          valueHeader="오차 ± σ"
          minSamples={minSamples}
          rows={leg.predictionRows.map((r) => ({
            ...r,
            text: formatSecSpread(r.biasSec, r.stddevSec, true),
          }))}
        />
      )}
      <UsedValue
        label={fallbackLabel(leg.predictionRows.length, prediction)}
        text={formatSecSpread(prediction.value.mean, prediction.value.stddev, true)}
      />

      <h3>차내 시간 (승차 → 하차)</h3>
      {leg.travelTimeRows.length === 0 ? (
        <p className="empty">{EMPTY_TEXT}</p>
      ) : (
        <BandTable
          caption="차내 시간"
          valueHeader="평균 ± σ"
          minSamples={minSamples}
          rows={leg.travelTimeRows.map((r) => ({
            ...r,
            text: formatSecSpread(r.meanSec, r.stddevSec),
          }))}
        />
      )}
      {travel && (
        <UsedValue
          label={fallbackLabel(leg.travelTimeRows.length, travel)}
          text={formatSecSpread(travel.value.mean, travel.value.stddev)}
        />
      )}
    </article>
  );
}

/** 행이 있으면 "샘플이 부족한 시간대에는 …", 하나도 없으면 그냥 출처. */
function fallbackLabel(rowCount: number, used: Resolved): string {
  const source = TRANSIT_SOURCE[used.source] + sampleNote(used);
  return rowCount === 0 ? source : `샘플이 부족한 시간대에는 ${source}`;
}

type BandRow = (PredictionCalibrationRow | TravelTimeCalibrationRow) & { text: string };

function BandTable({
  caption,
  valueHeader,
  rows,
  minSamples,
}: {
  caption: string;
  valueHeader: string;
  rows: BandRow[];
  minSamples: number;
}) {
  return (
    <table aria-label={caption}>
      <thead>
        <tr>
          <th>요일</th>
          <th>시간대 (KST)</th>
          <th>{valueHeader}</th>
          <th>샘플</th>
          <th>갱신</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => {
          const low = isLowSample(r.sampleCount, minSamples);
          return (
            <tr key={`${r.dayType}-${r.timeBandStart}`} className={low ? "low-sample" : undefined}>
              <td>{dayTypeLabel(r.dayType)}</td>
              <td>
                {r.timeBandStart}–{r.timeBandEnd}
              </td>
              <td>{r.text}</td>
              <td>
                {r.sampleCount}개 <LowBadge sampleCount={r.sampleCount} minSamples={minSamples} />
              </td>
              <td className="muted small">{formatKst(r.updatedAt)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
