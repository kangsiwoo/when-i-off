import { QueryClient } from "@tanstack/react-query";
import type { RouteObject, createBrowserRouter } from "react-router";
import { ApiError, createApiClient, type ApiClient } from "./api/client";
import { currentPath, safeReturnPath } from "./auth/returnPath";
import { routes } from "./routes";

type AppRouter = ReturnType<typeof createBrowserRouter>;

export interface AppContext {
  router: AppRouter;
  queryClient: QueryClient;
  api: ApiClient;
}

/**
 * 라우터·쿼리 캐시·API 클라이언트를 한 번에 묶는다. 401이면 캐시를 비우고 로그인 화면으로 보낸다(원래 경로는 state.from).
 * 브라우저는 createBrowserRouter, 테스트는 createMemoryRouter를 넘긴다.
 */
export function createAppContext(options: {
  createRouter: (routes: RouteObject[]) => AppRouter;
  fetch?: (request: Request) => Promise<Response>;
  baseUrl?: string;
}): AppContext {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        // 4xx는 다시 시도해도 같다. 5xx·네트워크 오류만 한 번 더.
        retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 1,
      },
    },
  });
  const router = options.createRouter(routes);
  const api = createApiClient({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
    onUnauthorized: () => {
      queryClient.clear();
      // 다시 로그인하면 401을 받은 화면으로 돌아오게 지금 경로를 넘긴다.
      const from = safeReturnPath(currentPath(router.state.location));
      void router.navigate("/login", { replace: true, state: { reason: "unauthorized", from } });
    },
  });
  return { router, queryClient, api };
}
