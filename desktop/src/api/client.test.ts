import { describe, expect, it, vi } from "vitest";
import { setToken, getToken } from "../auth/token";
import { json } from "../test/render";
import { ApiError, createApiClient, unwrap } from "./client";

describe("api client", () => {
  it("sends X-Api-Token on every request", async () => {
    setToken("devtoken");
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => json([]));
    const api = createApiClient({ baseUrl: "http://wio.test", fetch });

    await api.GET("/api/v1/commute-routes");

    const request = fetch.mock.calls[0]![0];
    expect(request.url).toBe("http://wio.test/api/v1/commute-routes");
    expect(request.headers.get("X-Api-Token")).toBe("devtoken");
  });

  it("on 401 clears the token, calls onUnauthorized and surfaces the problem detail", async () => {
    setToken("stale");
    const onUnauthorized = vi.fn();
    const fetch = vi.fn(async () =>
      json(
        { title: "Unauthorized", status: 401, detail: "missing or invalid X-Api-Token header" },
        401,
      ),
    );
    const api = createApiClient({ baseUrl: "http://wio.test", fetch, onUnauthorized });

    const result = await api.GET("/api/v1/commute-routes");

    expect(getToken()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledOnce();
    expect(() => unwrap(result)).toThrow(ApiError);
    expect(() => unwrap(result)).toThrow("missing or invalid X-Api-Token header");
  });

  it("does not treat other errors as logout", async () => {
    setToken("devtoken");
    const onUnauthorized = vi.fn();
    const fetch = vi.fn(async () => json({ status: 404, detail: "not found" }, 404));
    const api = createApiClient({ baseUrl: "http://wio.test", fetch, onUnauthorized });

    await api.GET("/api/v1/commute-routes/{id}", { params: { path: { id: 1 } } });

    expect(getToken()).toBe("devtoken");
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
