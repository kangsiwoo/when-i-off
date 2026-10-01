import type {
  BoardingAttempt,
  CommuteRoute,
  CommuteRouteDetail,
  CommuteTrip,
  RecommendationHistoryDay,
  RouteCalibration,
} from "../api/client";

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

/** `detail`의 두 구간에 대한 보정 상태. 도보 구간 행은 샘플 3개(신뢰도 낮음) → 전역 행이 쓰인다. */
export const calibration: RouteCalibration = {
  routeId: 7,
  minSamples: 5,
  globalWalkingProfile: {
    avgSpeedMps: 1.25,
    stddevSpeedMps: 0.2,
    sampleCount: 30,
    updatedAt: "2026-09-30T18:00:00Z",
  },
  legs: [
    {
      routeLegId: 12,
      seqOrder: 2,
      legType: "TRANSIT",
      plannedTravelSec: 1200,
      transitLineId: 3,
      transitLineName: "GTX-A",
      boardStopId: 4,
      boardStopName: "동탄",
      alightStopId: 5,
      alightStopName: "수서",
      predictionRows: [
        {
          dayType: "WEEKDAY",
          timeBandStart: "07:30",
          timeBandEnd: "08:00",
          biasSec: 20,
          stddevSec: 45,
          sampleCount: 12,
          updatedAt: "2026-09-30T18:00:00Z",
        },
        {
          dayType: "SATURDAY",
          timeBandStart: "23:30",
          timeBandEnd: "24:00",
          biasSec: -10,
          stddevSec: 30,
          sampleCount: 2,
          updatedAt: "2026-09-30T18:00:00Z",
        },
      ],
      travelTimeRows: [],
    },
    {
      routeLegId: 11,
      seqOrder: 1,
      legType: "WALK",
      plannedDistanceM: 650,
      plannedTravelSec: 480,
      walkingProfile: {
        avgSpeedMps: 1.4,
        stddevSpeedMps: 0.1,
        sampleCount: 3,
        updatedAt: "2026-09-30T18:00:00Z",
      },
      predictionRows: [],
      travelTimeRows: [],
    },
  ],
};

/** 추천 vs 실제(#62). 9/21: 두 버전 + trip 두 건, 9/22: 추천만, 9/23: trip만(지각). */
export const recommendationHistory: RecommendationHistoryDay[] = [
  {
    date: "2026-09-21",
    recommendations: [
      {
        recommendedLeaveHomeAt: "2026-09-20T22:24:00Z",
        targetArrivalAt: "2026-09-21T00:00:00Z",
        catchProbability: 0.91,
        bufferSeconds: 660,
        modelVersion: "v1",
        computedAt: "2026-09-20T21:00:00Z",
      },
      {
        recommendedLeaveHomeAt: "2026-09-20T22:30:00Z",
        targetArrivalAt: "2026-09-21T00:00:00Z",
        catchProbability: 0.88,
        bufferSeconds: 420,
        modelVersion: "v2",
        computedAt: "2026-09-20T21:30:00Z",
      },
    ],
    trips: [
      {
        tripId: 31,
        leftHomeAt: "2026-09-20T22:27:10Z",
        arrivedDestinationAt: "2026-09-20T23:58:00Z",
        allLegsCaught: true,
        missedCount: 1,
      },
      {
        tripId: 32,
        leftHomeAt: "2026-09-21T09:00:00Z",
        allLegsCaught: false,
        missedCount: 0,
      },
    ],
  },
  {
    date: "2026-09-22",
    recommendations: [
      {
        recommendedLeaveHomeAt: "2026-09-21T22:26:00Z",
        targetArrivalAt: "2026-09-22T00:00:00Z",
        catchProbability: 0.9,
        bufferSeconds: 540,
        modelVersion: "v1",
        computedAt: "2026-09-21T21:00:00Z",
      },
    ],
    trips: [],
  },
  {
    date: "2026-09-23",
    recommendations: [],
    trips: [
      {
        tripId: 33,
        leftHomeAt: "2026-09-22T22:35:00Z",
        arrivedDestinationAt: "2026-09-23T00:04:00Z",
        allLegsCaught: true,
        missedCount: 0,
      },
    ],
  },
];
