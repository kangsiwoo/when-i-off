import { describe, expect, it } from "vitest";
import { boundsOf, haversineM, isValidLatLng, midpoint, walkDistanceM } from "./geo";

describe("geo", () => {
  it("haversine: 1/1000 degree of latitude is ~111 m", () => {
    expect(haversineM({ lat: 37.2, lng: 127.07 }, { lat: 37.201, lng: 127.07 })).toBeCloseTo(
      111.2,
      0,
    );
    expect(haversineM({ lat: 37.2, lng: 127.07 }, { lat: 37.2, lng: 127.07 })).toBe(0);
  });

  it("walkDistanceM rounds and never returns 0 (server wants a positive distance)", () => {
    expect(walkDistanceM({ lat: 37.2, lng: 127.07 }, { lat: 37.2, lng: 127.07 })).toBe(1);
    expect(walkDistanceM({ lat: 37.2, lng: 127.07 }, { lat: 37.201, lng: 127.07 })).toBe(111);
  });

  it("boundsOf / midpoint", () => {
    expect(boundsOf([])).toBeNull();
    expect(
      boundsOf([
        { lat: 37.5, lng: 127.0 },
        { lat: 37.2, lng: 127.1 },
      ]),
    ).toEqual([
      [37.2, 127.0],
      [37.5, 127.1],
    ]);
    expect(midpoint({ lat: 0, lng: 0 }, { lat: 2, lng: 4 })).toEqual({ lat: 1, lng: 2 });
  });

  it("isValidLatLng uses the inclusive WGS84 range", () => {
    expect(isValidLatLng({ lat: 90, lng: -180 })).toBe(true);
    expect(isValidLatLng({ lat: 90.0001, lng: 0 })).toBe(false);
    expect(isValidLatLng({ lat: NaN, lng: 0 })).toBe(false);
  });
});
