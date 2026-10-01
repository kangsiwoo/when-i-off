// API 토큰은 localStorage에 둔다. 1인 사용 단계의 고정 토큰이라 이 정도로 충분하다 (docs/API.md "인증").
// 사파리 사생활 보호 모드 등에서 storage 접근 자체가 예외를 던질 수 있어 모두 감싼다.
const KEY = "wio.apiToken";

export function getToken(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string): void {
  try {
    localStorage.setItem(KEY, token);
  } catch {
    // 저장이 안 되면 다음 요청이 401을 받고 로그인 화면으로 돌아온다.
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // 지울 수 없으면 남은 토큰으로 다시 401을 받는다.
  }
}
