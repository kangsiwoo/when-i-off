import { useSearchParams } from "react-router";
import {
  useCommuteRouteDetails,
  useCommuteRoutes,
  useCommuteTrips,
  type TripFilter,
} from "../api/queries";
import { ErrorMessage, Loading } from "./Status";
import { TripTicket } from "./TripTicket";

/** 필터는 주소(`?routeId=&from=&to=`)에 둔다 — 상세에서 돌아와도 그대로다. */
function useTripFilter(): [TripFilter, (key: keyof TripFilter, value: string) => void] {
  const [params, setParams] = useSearchParams();
  const routeId = Number(params.get("routeId"));
  const filter: TripFilter = {
    ...(Number.isInteger(routeId) && routeId > 0 ? { routeId } : {}),
    ...(params.get("from") ? { from: params.get("from")! } : {}),
    ...(params.get("to") ? { to: params.get("to")! } : {}),
  };
  const set = (key: keyof TripFilter, value: string) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true },
    );
  return [filter, set];
}

export function TripListPage() {
  const [filter, setFilter] = useTripFilter();
  const routes = useCommuteRoutes();
  const trips = useCommuteTrips(filter);
  const routeIds = [...new Set(trips.data?.map((t) => t.routeId))];
  const details = useCommuteRouteDetails(routeIds);
  const legsOf = (routeId: number) => details[routeIds.indexOf(routeId)]?.data?.legs;
  const routeName = (id: number) => routes.data?.find((r) => r.id === id)?.name ?? `경로 #${id}`;

  return (
    <section>
      <h1>이동 기록</h1>
      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <label>
          경로
          <select
            value={filter.routeId ?? ""}
            onChange={(e) => setFilter("routeId", e.target.value)}
          >
            <option value="">전체</option>
            {routes.data?.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          시작일
          <input
            type="date"
            value={filter.from ?? ""}
            onChange={(e) => setFilter("from", e.target.value)}
          />
        </label>
        <label>
          종료일
          <input
            type="date"
            value={filter.to ?? ""}
            onChange={(e) => setFilter("to", e.target.value)}
          />
        </label>
      </form>

      {trips.isPending ? (
        <Loading />
      ) : trips.error ? (
        <ErrorMessage error={trips.error} />
      ) : trips.data.length === 0 ? (
        <p className="muted">조건에 맞는 기록이 없습니다.</p>
      ) : (
        <ol className="ticket-list" aria-label="승차권">
          {trips.data.map((trip) => (
            <li key={trip.id}>
              <TripTicket
                compact
                trip={trip}
                route={routes.data?.find((r) => r.id === trip.routeId)}
                routeName={routeName(trip.routeId)}
                legs={legsOf(trip.routeId)}
                // 단건 조회 API가 없어 상세는 경로·날짜로 좁힌 목록에서 찾는다
                href={`/trips/${trip.id}?routeId=${trip.routeId}&date=${trip.tripDate}`}
              />
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
