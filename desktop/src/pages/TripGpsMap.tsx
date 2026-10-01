import type { CommuteTrip, RouteLeg } from "../api/client";
import { useTripGpsTraces } from "../api/queries";
import { formatKstTime } from "../kst";
import { LazyMap } from "../map/LazyMap";
import { gpsOverlay } from "../map/overlay";
import { ErrorMessage, Loading } from "./Status";

/** trip 상세의 GPS 트랙 (#64): 기록 시각 순 폴리라인 + 시작/끝, 경로의 승하차 정류장. */
export function TripGpsMap({ trip, legs }: { trip: CommuteTrip; legs?: RouteLeg[] }) {
  const traces = useTripGpsTraces(trip.id);
  if (traces.isPending) return <Loading />;
  if (traces.error) return <ErrorMessage error={traces.error} />;
  const { markers, lines } = gpsOverlay(traces.data, legs ?? [], trip.boardingAttempts);
  const first = traces.data[0];
  const last = traces.data[traces.data.length - 1];
  return (
    <>
      <p className="muted small">
        {traces.data.length === 0
          ? "이 기록에 묶인 GPS 포인트가 없습니다. 경로의 정류장만 표시합니다."
          : `포인트 ${traces.data.length}개 · ${formatKstTime(first!.recordedAt)} → ${formatKstTime(
              last!.recordedAt,
            )} (KST)`}
      </p>
      <LazyMap label="GPS 트랙 지도" markers={markers} lines={lines} fitKey={`trip-${trip.id}`} />
    </>
  );
}
