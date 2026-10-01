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
  type CreateCommuteRouteRequest,
  type RouteLegRequest,
  type TransitMode,
  type UpdateBoardingAttemptRequest,
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
