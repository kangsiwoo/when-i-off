/** 로그인 후 돌아갈 경로. 같은 출처의 앱 경로만 받는다(`//host`·`/\host`·절대 URL·`/login` 자신은 버린다). */
export function safeReturnPath(from: unknown): string {
  if (typeof from !== "string" || !from.startsWith("/")) return "/";
  if (from.startsWith("//") || from.startsWith("/\\")) return "/";
  const pathname = from.split(/[?#]/, 1)[0];
  if (pathname === "/login") return "/";
  return from;
}

/** 현재 위치를 경로·쿼리·해시까지 한 문자열로. */
export function currentPath(location: { pathname: string; search: string; hash: string }): string {
  return `${location.pathname}${location.search}${location.hash}`;
}
