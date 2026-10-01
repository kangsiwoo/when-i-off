import { render } from "@testing-library/react";
import { createMemoryRouter } from "react-router";
import { vi } from "vitest";
import { App } from "../App";
import { createAppContext } from "../context";

export const BASE = "http://wio.test";

type Handler = (request: Request) => Response | Promise<Response>;

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": status >= 400 ? "application/problem+json" : "application/json",
    },
  });
}

/** 앱 전체를 메모리 라우터로 띄운다. fetch는 받은 Request를 handler에 넘긴다. */
export function renderApp(path: string, handler: Handler) {
  const fetch = vi.fn(async (request: Request) => handler(request));
  const context = createAppContext({
    createRouter: (routes) => createMemoryRouter(routes, { initialEntries: [path] }),
    fetch,
    baseUrl: BASE,
  });
  render(<App context={context} />);
  return { fetch, router: context.router };
}
