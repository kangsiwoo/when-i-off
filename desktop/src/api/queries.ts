import {
  keepPreviousData,
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useApi } from "./ApiContext";
import {
  unwrap,
  type CommuteRouteDetail,
  type CreateCommuteRouteRequest,
  type RouteLegRequest,
  type SignalCrossingRequest,
  type TrafficSignalCycleRequest,
  type TransitMode,
  type UpdateBoardingAttemptRequest,
  type UpdateCommuteRouteRequest,
  type UpdateCommuteTripRequest,
} from "./client";

export function useCommuteRoutes() {
  const api = useApi();
  return useQuery({
    queryKey: ["commute-routes"],
    queryFn: async ({ signal }) => unwrap(await api.GET("/api/v1/commute-routes", { signal })),
  });
}

function routeQuery(api: ReturnType<typeof useApi>, id: number) {
  return {
    queryKey: ["commute-routes", id],
    queryFn: async ({ signal }: { signal: AbortSignal }) =>
      unwrap(await api.GET("/api/v1/commute-routes/{id}", { params: { path: { id } }, signal })),
  };
}

export function useCommuteRoute(id: number) {
  const api = useApi();
  return useQuery(routeQuery(api, id));
}

/** 여러 경로의 상세(구간 목록)를 한꺼번에. 캐시 키가 useCommuteRoute와 같다. */
export function useCommuteRouteDetails(ids: number[]) {
  const api = useApi();
  return useQueries({ queries: ids.map((id) => routeQuery(api, id)) });
}

/** 경로의 보정값·샘플 수 (#60). 읽기 전용이라 무효화하지 않는다 — 보정은 analytics 배치가 쓴다. */
export function useRouteCalibration(id: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["commute-routes", id, "calibration"],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/commute-routes/{id}/calibration", {
          params: { path: { id } },
          signal,
        }),
      ),
  });
}

/**
 * 추천 vs 실제 (#62). `from`·`to`는 KST 날짜(`yyyy-MM-dd`, 양끝 포함). 기간을 바꾸는 동안 앞 결과를 들고 있어
 * 차트가 깜빡이지 않는다(`isPlaceholderData`로 흐리게 표시).
 */
export function useRecommendationHistory(id: number, from: string, to: string, enabled = true) {
  const api = useApi();
  return useQuery({
    queryKey: ["commute-routes", id, "recommendation-history", from, to],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/commute-routes/{routeId}/recommendation-history", {
          params: { path: { routeId: id }, query: { from, to } },
          signal,
        }),
      ),
    placeholderData: keepPreviousData,
    enabled,
  });
}

/** `GET /commute-trips`의 필터. 날짜는 KST 기준 trip 날짜(`yyyy-MM-dd`). */
export interface TripFilter {
  routeId?: number;
  from?: string;
  to?: string;
}

// trip 단건 조회 API는 없다. 상세도 이 목록 조회(같은 날짜·경로로 좁힌)에서 꺼내므로
// 보정 뒤 ["commute-trips"] 하나만 무효화하면 목록·상세가 같이 새로 온다.
const TRIPS = "commute-trips";

export function useCommuteTrips(filter: TripFilter) {
  const api = useApi();
  return useQuery({
    queryKey: [TRIPS, filter],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/commute-trips", { params: { query: filter }, signal })),
  });
}

/** trip을 고치면 trip 목록·상세와 그 결과를 묶어 보여 주는 추천 vs 실제(#62)도 다시 받는다. */
function invalidateTrips(queryClient: ReturnType<typeof useQueryClient>) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: [TRIPS] }),
    queryClient.invalidateQueries({
      predicate: (q) => q.queryKey[2] === "recommendation-history",
    }),
  ]);
}

export function useUpdateTrip(id: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateCommuteTripRequest) =>
      unwrap(await api.PATCH("/api/v1/commute-trips/{id}", { params: { path: { id } }, body })),
    onSuccess: () => invalidateTrips(queryClient),
  });
}

export function useUpdateAttempt(id: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateBoardingAttemptRequest) =>
      unwrap(await api.PATCH("/api/v1/boarding-attempts/{id}", { params: { path: { id } }, body })),
    onSuccess: () => invalidateTrips(queryClient),
  });
}

// --- 경로 편집 (#64) ---

export function useCreateRoute() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateCommuteRouteRequest) =>
      unwrap(await api.POST("/api/v1/commute-routes", { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["commute-routes"], exact: true }),
  });
}

/** 경로의 상세·보정 상태 등 그 경로 아래 캐시를 모두 다시 받게 한다. */
function invalidateRoute(queryClient: ReturnType<typeof useQueryClient>, id: number) {
  return queryClient.invalidateQueries({ queryKey: ["commute-routes", id] });
}

/**
 * 이름·사용 여부·기본 목표 도착 시각·대상 day_type 일부 수정 (#68). 응답(경로 한 건)을 상세 캐시의 `route`에
 * 바로 반영하고 목록·상세를 다시 받는다.
 */
export function useUpdateRoute(id: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateCommuteRouteRequest) =>
      unwrap(await api.PATCH("/api/v1/commute-routes/{id}", { params: { path: { id } }, body })),
    onSuccess: (route) => {
      queryClient.setQueryData<CommuteRouteDetail>(["commute-routes", id], (prev) =>
        prev ? { ...prev, route } : prev,
      );
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: ["commute-routes"], exact: true }),
        invalidateRoute(queryClient, id),
      ]);
    },
  });
}

export function useReplaceLegs(id: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (legs: RouteLegRequest[]) =>
      unwrap(
        await api.PUT("/api/v1/commute-routes/{id}/legs", {
          params: { path: { id } },
          body: { legs },
        }),
      ),
    onSuccess: (data) => {
      queryClient.setQueryData(["commute-routes", id], data);
      return invalidateRoute(queryClient, id);
    },
  });
}

export function useReplaceCrossings(routeId: number, legId: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (crossings: SignalCrossingRequest[]) =>
      unwrap(
        await api.PUT("/api/v1/route-legs/{id}/signal-crossings", {
          params: { path: { id: legId } },
          body: { crossings },
        }),
      ),
    onSuccess: () => invalidateRoute(queryClient, routeId),
  });
}

export function useTransitLineSearch(keyword: string, mode: TransitMode | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["transit-lines", "search", keyword, mode ?? null],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/transit-lines/search", {
          params: { query: { keyword, ...(mode ? { mode } : {}) } },
          signal,
        }),
      ),
    enabled: keyword.trim() !== "",
  });
}

export interface NearbyQuery {
  lat: number;
  lng: number;
  radiusM: number;
}

export function useNearbyStops(at: NearbyQuery | null, mode: TransitMode | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["transit-stops", "nearby", at, mode ?? null],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/transit-stops/nearby", {
          params: { query: { ...at!, ...(mode ? { mode } : {}) } },
          signal,
        }),
      ),
    enabled: at != null,
  });
}

export function useNearbySignals(at: NearbyQuery | null) {
  const api = useApi();
  return useQuery({
    queryKey: ["traffic-signals", "nearby", at],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/traffic-signals/nearby", { params: { query: at! }, signal })),
    enabled: at != null,
  });
}

export function useCreateSignal() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: { lat: number; lng: number; name?: string }) =>
      unwrap(await api.POST("/api/v1/traffic-signals", { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["traffic-signals"] }),
  });
}

/** 교차로의 신호 주기 행 전부 (#78). 출처 무관, day_type → 시간대 → 출처 우선순위 순. */
export function useSignalCycles(signalId: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["traffic-signals", signalId, "cycles"],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/traffic-signals/{id}/cycles", {
          params: { path: { id: signalId } },
          signal,
        }),
      ),
  });
}

/** `USER_OBSERVED` 행 전체 교체 (#78). 응답(교체 후 전체 목록)을 바로 캐시에 넣는다. */
export function useReplaceSignalCycles(signalId: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (cycles: TrafficSignalCycleRequest[]) =>
      unwrap(
        await api.PUT("/api/v1/traffic-signals/{id}/cycles", {
          params: { path: { id: signalId } },
          body: { cycles },
        }),
      ),
    onSuccess: (rows) => queryClient.setQueryData(["traffic-signals", signalId, "cycles"], rows),
  });
}

/** trip의 GPS 트랙 (#64). 기록 시각 순. */
export function useTripGpsTraces(tripId: number) {
  const api = useApi();
  return useQuery({
    queryKey: [TRIPS, tripId, "gps-traces"],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/commute-trips/{id}/gps-traces", {
          params: { path: { id: tripId } },
          signal,
        }),
      ),
  });
}

// --- 정적 시간표 (#70) ---

/**
 * 정적 시간표 CSV 업로드 (`POST /admin/schedules/import`, multipart 파트 `file`). 생성 타입은 binary를 `string`으로
 * 두므로 본문은 타입만 맞추고, 실제 파트는 bodySerializer가 File 그대로 FormData에 담는다(Content-Type과
 * boundary는 브라우저가 붙인다). 성공하면 다음 출발 조회 캐시를 버린다.
 */
export function useImportSchedules() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) =>
      unwrap(
        await api.POST("/api/v1/admin/schedules/import", {
          body: { file: file.name },
          bodySerializer: () => {
            const form = new FormData();
            form.append("file", file, file.name);
            return form;
          },
        }),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ predicate: (q) => q.queryKey[0] === NEXT_DEPARTURES }),
  });
}

const NEXT_DEPARTURES = "schedules-next";

export interface NextDeparturesQuery {
  lineId: number;
  stopId: number;
  direction: string;
  /** UTC ISO. */
  at: string;
  limit: number;
}

/** 정적 시간표 기준 다음 출발 (`GET /transit-lines/{id}/schedules/next`). `q`가 null이면 부르지 않는다. */
export function useNextDepartures(q: NextDeparturesQuery | null) {
  const api = useApi();
  return useQuery({
    queryKey: [NEXT_DEPARTURES, q],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/transit-lines/{lineId}/schedules/next", {
          params: {
            path: { lineId: q!.lineId },
            query: { stopId: q!.stopId, direction: q!.direction, at: q!.at, limit: q!.limit },
          },
          signal,
        }),
      ),
    enabled: q != null,
  });
}

// --- 운영 (#76) ---

/** 운영 화면 자동 새로 고침 간격. 집계가 분 단위라 이보다 자주 받을 이유가 없다. */
export const OPS_REFRESH_MS = 30_000;

/** 외부 API 호출 집계 + 폴링·보관 배치 상태 (`GET /admin/ops/external-apis`). 30초마다 다시 받는다. */
export function useOpsStatus() {
  const api = useApi();
  return useQuery({
    queryKey: ["admin-ops", "external-apis"],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/admin/ops/external-apis", { signal })),
    refetchInterval: OPS_REFRESH_MS,
  });
}
