import { describe, expect, it, vi } from "vitest";
import { clearToken, getToken, setToken } from "./token";

describe("token storage", () => {
  it("stores, reads and clears the token in localStorage", () => {
    expect(getToken()).toBeNull();
    setToken("devtoken");
    expect(getToken()).toBe("devtoken");
    expect(localStorage.getItem("wio.apiToken")).toBe("devtoken");
    clearToken();
    expect(getToken()).toBeNull();
  });

  it("treats an unavailable storage as no token", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("denied", "SecurityError");
    });
    expect(getToken()).toBeNull();
  });
});
