import { Navigate, Outlet, useLocation } from "react-router";
import { getToken } from "./token";

/** 토큰이 없으면 로그인 화면으로. 로그인 후 원래 가려던 곳으로 돌아오게 경로를 넘긴다. */
export function RequireAuth() {
  const location = useLocation();
  if (!getToken()) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}
