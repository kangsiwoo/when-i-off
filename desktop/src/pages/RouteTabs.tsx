import { NavLink } from "react-router";

/** 경로 상세의 탭: 구간 목록 / 구간 편집(지도, #64) / 보정 상태(#60) / 추천 vs 실제(#62). */
export function RouteTabs({ id }: { id: number }) {
  return (
    <nav className="tabs" aria-label="경로 보기">
      <NavLink to={`/routes/${id}`} end>
        구간
      </NavLink>
      <NavLink to={`/routes/${id}/edit`}>구간 편집</NavLink>
      <NavLink to={`/routes/${id}/calibration`}>보정 상태</NavLink>
      <NavLink to={`/routes/${id}/recommendations`}>추천 vs 실제</NavLink>
    </nav>
  );
}
