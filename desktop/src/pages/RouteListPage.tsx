import { Link } from "react-router";
import { useCommuteRoutes } from "../api/queries";
import { directionLabel, formatKst } from "../format";
import { ErrorMessage, Loading } from "./Status";

export function RouteListPage() {
  const { data: routes, error, isPending } = useCommuteRoutes();

  return (
    <section>
      <div className="page-head">
        <h1>출퇴근 경로</h1>
        <Link to="/routes/new" className="button-link">
          + 새 경로
        </Link>
      </div>
      {isPending ? (
        <Loading />
      ) : error ? (
        <ErrorMessage error={error} />
      ) : routes.length === 0 ? (
        <p className="muted">등록된 경로가 없습니다.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>이름</th>
              <th>방향</th>
              <th>상태</th>
              <th>등록</th>
            </tr>
          </thead>
          <tbody>
            {routes.map((route) => (
              <tr key={route.id}>
                <td>
                  <Link to={`/routes/${route.id}`}>{route.name}</Link>
                </td>
                <td>{directionLabel(route.direction)}</td>
                <td>{route.isActive ? "사용 중" : "꺼짐"}</td>
                <td>{formatKst(route.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
