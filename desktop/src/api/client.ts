import createClient, { type Middleware } from "openapi-fetch";
import { clearToken, getToken } from "../auth/token";
import type { components, paths } from "./schema";

export const TOKEN_HEADER = "X-Api-Token";

export type ApiClient = ReturnType<typeof createClient<paths>>;

// 화면에서 쓰는 이름. 모양은 전부 생성된 스키마에서 온다 (손으로 정의하지 않는다).
export type CommuteRoute = components["schemas"]["CommuteRouteResponse"];
export type CommuteRouteDetail = components["schemas"]["CommuteRouteDetailResponse"];
export type RouteLeg = components["schemas"]["RouteLegResponse"];
export type CommuteTrip = components["schemas"]["CommuteTripResponse"];
export type BoardingAttempt = components["schemas"]["BoardingAttemptResponse"];
export type AttemptResult = BoardingAttempt["result"];
export type UpdateCommuteTripRequest = components["schemas"]["UpdateCommuteTripRequest"];
export type UpdateBoardingAttemptRequest = components["schemas"]["UpdateBoardingAttemptRequest"];

export interface ApiClientOptions {
  /** 기본은 같은 출처. 개발/미리보기 서버가 /api를 backend로 넘긴다 (vite.config.ts). */
  baseUrl?: string;
  /** 테스트에서 가짜 fetch를 넣는다. openapi-fetch는 Request 하나로 부른다. */
  fetch?: (request: Request) => Promise<Response>;
  /** 401을 받았을 때. 토큰은 이미 지워진 상태로 불린다. */
  onUnauthorized?: () => void;
}

/** 모든 요청에 X-Api-Token을 붙이고, 401이면 토큰을 지운 뒤 onUnauthorized를 부른다. */
export function authMiddleware(onUnauthorized: () => void): Middleware {
  return {
    onRequest({ request }) {
      const token = getToken();
      if (token) request.headers.set(TOKEN_HEADER, token);
      return request;
    },
    onResponse({ response }) {
      if (response.status === 401) {
        clearToken();
        onUnauthorized();
      }
      return response;
    },
  };
}

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? "",
    ...(options.fetch ? { fetch: options.fetch } : {}),
  });
  client.use(authMiddleware(options.onUnauthorized ?? (() => window.location.assign("/login"))));
  return client;
}

/** RFC 7807 응답(또는 그 밖의 실패)을 감싼다. `detail`이 있으면 메시지로 쓴다. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: unknown;

  constructor(status: number, problem: unknown) {
    const detail =
      typeof problem === "object" && problem !== null && "detail" in problem
        ? String(problem.detail)
        : undefined;
    super(detail ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

/** openapi-fetch 결과에서 data를 꺼내거나 ApiError를 던진다 (TanStack Query의 queryFn용). */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (!result.response.ok || result.data === undefined) {
    throw new ApiError(result.response.status, result.error);
  }
  return result.data;
}
