import type { CommuteRoute, CommuteRouteDetail } from "../api/client";

export const route: CommuteRoute = {
  id: 7,
  name: "집 → 회사",
  direction: "TO_WORK",
  originLat: 37.2,
  originLng: 127.07,
  destinationLat: 37.49,
  destinationLng: 127.1,
  isActive: true,
  createdAt: "2026-09-10T00:00:00Z",
};

export const detail: CommuteRouteDetail = {
  route,
  legs: [
    // 응답 순서가 어긋나도 seqOrder로 정렬해 보여 준다.
    {
      id: 12,
      seqOrder: 2,
      legType: "TRANSIT",
      transitLineId: 3,
      boardStopId: 4,
      alightStopId: 5,
      plannedTravelSec: 1200,
      transitLine: {
        id: 3,
        mode: "GTX",
        name: "GTX-A",
        hasRealtimeApi: false,
        createdAt: "2026-09-10T00:00:00Z",
      },
      boardStop: {
        id: 4,
        mode: "GTX",
        name: "동탄",
        lat: 37.2,
        lng: 127.09,
        createdAt: "2026-09-10T00:00:00Z",
      },
      alightStop: {
        id: 5,
        mode: "GTX",
        name: "수서",
        lat: 37.48,
        lng: 127.1,
        createdAt: "2026-09-10T00:00:00Z",
      },
      signalCrossings: [],
    },
    {
      id: 11,
      seqOrder: 1,
      legType: "WALK",
      startLat: 37.2,
      startLng: 127.07,
      endLat: 37.2,
      endLng: 127.09,
      plannedDistanceM: 650,
      plannedTravelSec: 480,
      signalCrossings: [
        { id: 1, trafficSignalId: 9, seqOrder: 1, approachDir: "nt", signalKind: "Pd" },
      ],
    },
  ],
};
