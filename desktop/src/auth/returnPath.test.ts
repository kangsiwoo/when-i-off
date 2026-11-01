import { describe, expect, it } from "vitest";
import { currentPath, safeReturnPath } from "./returnPath";

describe("safeReturnPath", () => {
  it("keeps an in-app path with query and hash", () => {
    expect(safeReturnPath("/routes/7/recommendations?from=2026-01-01#chart")).toBe(
      "/routes/7/recommendations?from=2026-01-01#chart",
    );
  });

  it.each([
    undefined,
    null,
    42,
    "",
    "routes/7",
    "https://evil.test/",
    "//evil.test",
    "/\\evil.test",
  ])("falls back to / for %s", (from) => {
    expect(safeReturnPath(from)).toBe("/");
  });

  it("does not loop back to the login page", () => {
    expect(safeReturnPath("/login")).toBe("/");
    expect(safeReturnPath("/login?x=1")).toBe("/");
  });
});

describe("currentPath", () => {
  it("joins pathname, search and hash", () => {
    expect(currentPath({ pathname: "/trips", search: "?page=2", hash: "#top" })).toBe(
      "/trips?page=2#top",
    );
  });
});
