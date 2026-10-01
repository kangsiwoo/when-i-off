import type { TripSummary } from "../trips/timeline";

export function TripResult({ summary }: { summary: TripSummary }) {
  const { allCaught, caughtLegs, transitLegs, missed } = summary;
  return (
    <span>
      {transitLegs === 0 ? (
        <span className="muted">탑승 기록 없음</span>
      ) : allCaught ? (
        <span className="badge badge-caught">전 구간 탑승</span>
      ) : (
        <span className="badge badge-incomplete">
          {caughtLegs}/{transitLegs} 구간 탑승
        </span>
      )}
      {missed > 0 && <span className="missed"> 놓친 차 {missed}대</span>}
    </span>
  );
}
