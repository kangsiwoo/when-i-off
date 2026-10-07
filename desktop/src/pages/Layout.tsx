import { useQueryClient } from "@tanstack/react-query";
import { Link, Outlet, useLocation, useNavigate } from "react-router";
import logo from "../assets/whenioff_logo.svg";
import { clearToken } from "../auth/token";

// 머리 메뉴: 소개 페이지 머리와 같은 워드마크 + 번호 붙은 모노 메뉴(번호는 CSS가 data-no로 그리고 화면 읽기에서 뺀다).
const NAV = [
  { to: "/", label: "경로", no: "01", match: (p: string) => p === "/" || p.startsWith("/routes") },
  { to: "/trips", label: "이동 기록", no: "02", match: (p: string) => p.startsWith("/trips") },
  { to: "/schedules", label: "시간표", no: "03", match: (p: string) => p.startsWith("/schedules") },
  { to: "/ops", label: "운영", no: "04", match: (p: string) => p.startsWith("/ops") },
];

export function Layout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { pathname } = useLocation();

  const logout = () => {
    clearToken();
    queryClient.clear();
    void navigate("/login", { replace: true });
  };

  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="brand" aria-label="When I Off 홈">
          <img src={logo} alt="" width={28} height={28} />
          <span>When I Off</span>
        </Link>
        <nav aria-label="주 메뉴">
          {NAV.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              data-no={item.no}
              aria-current={item.match(pathname) ? "page" : undefined}
            >
              {item.label}
            </Link>
          ))}
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
