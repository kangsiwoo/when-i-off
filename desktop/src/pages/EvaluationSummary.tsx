import type { RecommendationEvaluations, RecommendationHistoryDay } from "../api/client";
import {
  buildSummaryCards,
  historyVersions,
  LOW_SAMPLE_N,
  missingEvaluationReason,
  type VersionSummaryCard,
} from "../recommendations/evaluations";

// 버전별 성과 요약(#79). 차트 위에 버전마다 한 줄의 stat tile. 버전 색은 차트와 같은 규칙(번호 기반)이고
// 색은 견본(사각형)에만 쓴다 — 글자는 늘 잉크색이다.

const swatch = (card: VersionSummaryCard) =>
  `var(--${card.slot === "other" ? "series-other" : card.slot})`;

export function EvaluationSummary({
  data,
  days,
  from,
  to,
}: {
  data: RecommendationEvaluations;
  days: RecommendationHistoryDay[];
  from: string;
  to: string;
}) {
  const cards = buildSummaryCards(data.summaries, historyVersions(days));
  return (
    <section className="eval-summary" aria-labelledby="eval-summary-title">
      <h2 id="eval-summary-title">버전별 성과</h2>
      {cards.length === 0 ? (
        <EmptyEvaluations days={days} from={from} to={to} />
      ) : (
        <>
          <p className="viz-sub">
            추천대로 나갔을 때 실제로 어땠는지, 모델 버전마다 이 기간({from} ~ {to})의 평가로 낸
            값입니다. 평가는 매일 새벽 <code>wio-analytics evaluate</code>가 갱신합니다.
          </p>
          {cards.map((card) => (
            <VersionRow key={card.modelVersion} card={card} />
          ))}
        </>
      )}
    </section>
  );
}

function VersionRow({ card }: { card: VersionSummaryCard }) {
  const titleId = `eval-${card.modelVersion}`;
  return (
    <div className="eval-version" role="group" aria-labelledby={titleId}>
      <div className="eval-version-head">
        <span className="eval-swatch" style={{ background: swatch(card) }} aria-hidden />
        <h3 id={titleId}>{card.modelVersion}</h3>
        {card.lowSample && (
          <span
            className="badge badge-low"
            title={`평가가 ${LOW_SAMPLE_N}일 미만이라 숫자가 흔들리기 쉽습니다`}
          >
            표본 적음
          </span>
        )}
      </div>
      <dl className="stat-tiles">
        {card.tiles.map((t) => (
          <div key={t.key} className="stat-tile">
            <dt>{t.label}</dt>
            <dd className="stat-value">{t.value}</dd>
            <dd className="stat-detail">{t.detail}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function EmptyEvaluations({
  days,
  from,
  to,
}: {
  days: RecommendationHistoryDay[];
  from: string;
  to: string;
}) {
  if (missingEvaluationReason(days) === "nothing-to-evaluate") {
    return (
      <p className="empty">
        이 기간에는 평가할 날이 없습니다. 같은 날 추천과 이동 기록이 둘 다 있어야 평가합니다.
      </p>
    );
  }
  return (
    <div className="empty eval-empty">
      <p>
        이 기간({from} ~ {to})의 평가가 아직 없습니다. 평가는 analytics 배치가 만듭니다:
      </p>
      <pre>
        <code>
          cd analytics && uv run wio-analytics evaluate --from {from} --to {to}
        </code>
      </pre>
      <p className="small">
        운영에서는 cron이 매일 새벽 03:00(KST)에 도보 파생·보정 다음으로 돌립니다
        (analytics/cron.example). 오늘 기록은 다음 날 새벽에 반영됩니다.
      </p>
    </div>
  );
}
