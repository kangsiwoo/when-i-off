import { useQuery } from "@tanstack/react-query";
import { useApi } from "./ApiContext";
import { unwrap } from "./client";

export function useCommuteRoutes() {
  const api = useApi();
  return useQuery({
    queryKey: ["commute-routes"],
    queryFn: async ({ signal }) => unwrap(await api.GET("/api/v1/commute-routes", { signal })),
  });
}

export function useCommuteRoute(id: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["commute-routes", id],
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/commute-routes/{id}", { params: { path: { id } }, signal })),
  });
}
