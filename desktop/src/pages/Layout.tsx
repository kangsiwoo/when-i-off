import { useQueryClient } from "@tanstack/react-query";
import { Link, Outlet, useNavigate } from "react-router";
import { clearToken } from "../auth/token";

export function Layout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const logout = () => {
    clearToken();
    queryClient.clear();
    void navigate("/login", { replace: true });
  };

  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="brand">
          when-i-off
        </Link>
        <nav>
          <Link to="/">경로</Link>
          <Link to="/trips">이동 기록</Link>
          <Link to="/schedules">시간표</Link>
        </nav>
        <button type="button" className="link-button" onClick={logout}>
          로그아웃
        </button>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
