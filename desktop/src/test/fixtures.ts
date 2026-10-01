import type { BoardingAttempt, CommuteRoute, CommuteRouteDetail, CommuteTrip } from "../api/client";

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

/** 동탄 도착 07:42:30 KST → 07:43 차 놓침 → 07:58 차 탐 → 수서 하차 → 도착. 시각은 UTC. */
export const missedAttempt: BoardingAttempt = {
  id: 101,
  tripId: 50,
  routeLegId: 12,
  attemptSeq: 1,
  arrivedAtStopAt: "2026-09-10T22:42:30Z",
  vehicleScheduledOrPredictedAt: "2026-09-10T22:43:00Z",
  vehicleActualDepartureAt: "2026-09-10T22:43:20Z",
  result: "MISSED",
  createdAt: "2026-09-10T22:42:30Z",
};

export const caughtAttempt: BoardingAttempt = {
  id: 102,
  tripId: 50,
  routeLegId: 12,
  attemptSeq: 2,
  vehicleScheduledOrPredictedAt: "2026-09-10T22:58:00Z",
  vehicleActualDepartureAt: "2026-09-10T22:57:45Z",
  alightedAt: "2026-09-10T23:18:10Z",
  result: "CAUGHT",
  notes: "지하에서 하차가 늦게 잡힘",
  createdAt: "2026-09-10T22:43:20Z",
};

export const trip: CommuteTrip = {
  id: 50,
  routeId: 7,
  tripDate: "2026-09-11",
  leftHomeAt: "2026-09-10T22:30:00Z",
  arrivedDestinationAt: "2026-09-10T23:25:30Z",
  // 응답 순서가 어긋나도 attemptSeq로 정렬한다.
  boardingAttempts: [caughtAttempt, missedAttempt],
  createdAt: "2026-09-10T22:30:00Z",
};
