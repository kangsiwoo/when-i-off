import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useApi } from "./ApiContext";
import { unwrap, type UpdateBoardingAttemptRequest, type UpdateCommuteTripRequest } from "./client";

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

export function useUpdateTrip(id: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateCommuteTripRequest) =>
      unwrap(await api.PATCH("/api/v1/commute-trips/{id}", { params: { path: { id } }, body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [TRIPS] }),
  });
}

export function useUpdateAttempt(id: number) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateBoardingAttemptRequest) =>
      unwrap(await api.PATCH("/api/v1/boarding-attempts/{id}", { params: { path: { id } }, body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [TRIPS] }),
  });
}
