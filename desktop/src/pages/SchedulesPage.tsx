import { useState } from "react";
import { useCommuteRouteDetails, useCommuteRoutes } from "../api/queries";
import type { ScheduleRow } from "../schedules/csv";
import { draftFromRows, emptyDraft, knownTransit } from "../schedules/next";
import { NextDeparturesChecker } from "./NextDeparturesChecker";
import { ScheduleUpload } from "./ScheduleUpload";

/**
 * 정적 시간표 (#70): CSV 업로드(`POST /admin/schedules/import`)와 확인용 다음 출발
 * (`GET /transit-lines/{id}/schedules/next`). GTX처럼 실시간 API가 없는 노선은 이 시간표가 추천·예측 스냅샷의 근거다.
 */
export function SchedulesPage() {
  // 노선·정류장 단건 조회 API가 없어서 이름은 내 경로의 TRANSIT 구간에서 얻는다 (없으면 id로 보인다).
  const routes = useCommuteRoutes();
  const details = useCommuteRouteDetails((routes.data ?? []).map((r) => r.id));
  const known = knownTransit(details.map((d) => d.data));
  const [rows, setRows] = useState<ScheduleRow[]>([]);
  const [draft, setDraft] = useState(emptyDraft);

  return (
    <>
      <h1>시간표</h1>
      <p className="muted">
        실시간 API가 없는 노선(GTX 등)의 정적 시간표를 CSV로 올리고, 올린 결과를 다음 출발로
        확인합니다.
      </p>
      <div className="schedules">
        <ScheduleUpload
          known={known}
          onParsed={setRows}
          onUploaded={(uploaded) => setDraft((d) => draftFromRows(d, uploaded, known))}
        />
        <NextDeparturesChecker draft={draft} onDraft={setDraft} rows={rows} known={known} />
      </div>
    </>
  );
}
