import Foundation

// 백엔드 DTO와 1:1로 맞춘 모델 (backend `route/api/RouteDtos.kt`, `trip/api/TripDtos.kt`,
// `trip/api/DepartureRecommendationResponse.kt`). id는 백엔드 `Long`이라 `Int64`.
// 선택 필드는 백엔드가 null이면 키를 빼고 보내므로 전부 `Optional`이다 (``WhenIOffJSON``).

public enum CommuteDirection: String, Codable, Sendable {
    case toWork = "TO_WORK"
    case toHome = "TO_HOME"
}

public enum LegType: String, Codable, Sendable {
    case walk = "WALK"
    case transit = "TRANSIT"
}

public enum TransitMode: String, Codable, Sendable {
    case bus = "BUS"
    case subway = "SUBWAY"
    case gtx = "GTX"
}

public enum BoardingResult: String, Codable, Sendable {
    case caught = "CAUGHT"
    case missed = "MISSED"
    case unknown = "UNKNOWN"
}

// MARK: - 경로

public struct CommuteRoute: Codable, Sendable, Equatable, Identifiable {
    public let id: Int64
    public let name: String
    public let direction: CommuteDirection
    public let originLat: Double
    public let originLng: Double
    public let destinationLat: Double
    public let destinationLng: Double
    public let isActive: Bool
    public let createdAt: Date
}

public struct SignalCrossing: Codable, Sendable, Equatable, Identifiable {
    public let id: Int64
    public let trafficSignalId: Int64
    public let seqOrder: Int
    public let approachDir: String
    public let signalKind: String
}

/// 노선. 백엔드 `TransitLineResponse`.
public struct TransitLine: Codable, Sendable, Equatable, Identifiable {
    public let id: Int64
    public let mode: TransitMode
    /// 예: "GTX-A (수서~동탄)"
    public let name: String
    public let stdgCd: String?
    public let externalId: String?
    public let agency: String?
    /// `false`면 실시간 도착정보가 없어 정적 시간표로 추천한다 (GTX 등).
    public let hasRealtimeApi: Bool
    public let createdAt: Date
}

/// 정류장·역. 백엔드 `TransitStopResponse`. 정류장 geofence는 이 좌표로 건다.
public struct TransitStop: Codable, Sendable, Equatable, Identifiable {
    public let id: Int64
    public let mode: TransitMode
    public let name: String
    public let lat: Double
    public let lng: Double
    public let stdgCd: String?
    public let externalId: String?
    public let createdAt: Date
}

/// 경로의 한 구간. WALK면 시작/끝 좌표가, TRANSIT이면 노선과 승하차 정류장이 채워진다.
public struct RouteLeg: Codable, Sendable, Equatable, Identifiable {
    public let id: Int64
    public let seqOrder: Int
    public let legType: LegType
    public let startLat: Double?
    public let startLng: Double?
    public let endLat: Double?
    public let endLng: Double?
    public let plannedDistanceM: Double?
    public let transitLineId: Int64?
    public let boardStopId: Int64?
    public let alightStopId: Int64?
    public let plannedTravelSec: Int?
    /// TRANSIT 구간에서만 온다 (#36). 기록 화면의 "동탄 → 수서 (GTX-A)"와 정류장 geofence에 쓴다.
    public let transitLine: TransitLine?
    public let boardStop: TransitStop?
    public let alightStop: TransitStop?
    public let signalCrossings: [SignalCrossing]
}

public struct CommuteRouteDetail: Codable, Sendable, Equatable {
    public let route: CommuteRoute
    public let legs: [RouteLeg]

    /// 탑승 시도(boarding attempt)는 TRANSIT 구간마다 하나다. 기록 화면이 이 순서로 진행한다.
    public var transitLegs: [RouteLeg] {
        legs.filter { $0.legType == .transit }.sorted { $0.seqOrder < $1.seqOrder }
    }
}

// MARK: - 이동 기록

public struct BoardingAttempt: Codable, Sendable, Equatable, Identifiable {
    public let id: Int64
    public let tripId: Int64
    public let routeLegId: Int64
    public let arrivedAtStopAt: Date?
    public let vehicleScheduledOrPredictedAt: Date?
    public let vehicleActualDepartureAt: Date?
    public let alightedAt: Date?
    public let result: BoardingResult
    public let notes: String?
    public let createdAt: Date
}

public struct CommuteTrip: Codable, Sendable, Equatable, Identifiable {
    public let id: Int64
    public let routeId: Int64
    /// KST 달력 날짜. 시각이 아니다.
    public let tripDate: LocalDate
    public let leftHomeAt: Date?
    public let arrivedDestinationAt: Date?
    public let createdAt: Date
    public let boardingAttempts: [BoardingAttempt]
}

public struct CreateCommuteTripRequest: Codable, Sendable, Equatable {
    public var routeId: Int64
    public var tripDate: LocalDate
    public var leftHomeAt: Date?

    public init(routeId: Int64, tripDate: LocalDate, leftHomeAt: Date? = nil) {
        self.routeId = routeId
        self.tripDate = tripDate
        self.leftHomeAt = leftHomeAt
    }
}

/// PATCH. `nil`인 필드는 요청에서 빠지고 서버는 그 필드를 바꾸지 않는다.
public struct UpdateCommuteTripRequest: Codable, Sendable, Equatable {
    public var leftHomeAt: Date?
    public var arrivedDestinationAt: Date?

    public init(leftHomeAt: Date? = nil, arrivedDestinationAt: Date? = nil) {
        self.leftHomeAt = leftHomeAt
        self.arrivedDestinationAt = arrivedDestinationAt
    }
}

/// `(trip, routeLegId)` 기준 **upsert**라 같은 구간을 다시 보내도 행이 늘지 않는다.
public struct UpsertBoardingAttemptRequest: Codable, Sendable, Equatable {
    public var routeLegId: Int64
    public var arrivedAtStopAt: Date?
    public var vehicleScheduledOrPredictedAt: Date?
    public var vehicleActualDepartureAt: Date?
    public var alightedAt: Date?
    public var result: BoardingResult?
    public var notes: String?

    public init(
        routeLegId: Int64,
        arrivedAtStopAt: Date? = nil,
        vehicleScheduledOrPredictedAt: Date? = nil,
        vehicleActualDepartureAt: Date? = nil,
        alightedAt: Date? = nil,
        result: BoardingResult? = nil,
        notes: String? = nil
    ) {
        self.routeLegId = routeLegId
        self.arrivedAtStopAt = arrivedAtStopAt
        self.vehicleScheduledOrPredictedAt = vehicleScheduledOrPredictedAt
        self.vehicleActualDepartureAt = vehicleActualDepartureAt
        self.alightedAt = alightedAt
        self.result = result
        self.notes = notes
    }
}

/// PATCH. `nil`인 필드는 요청에서 빠지고 서버는 그 필드를 바꾸지 않는다.
public struct UpdateBoardingAttemptRequest: Codable, Sendable, Equatable {
    public var arrivedAtStopAt: Date?
    public var vehicleScheduledOrPredictedAt: Date?
    public var vehicleActualDepartureAt: Date?
    public var alightedAt: Date?
    public var result: BoardingResult?
    public var notes: String?

    public init(
        arrivedAtStopAt: Date? = nil,
        vehicleScheduledOrPredictedAt: Date? = nil,
        vehicleActualDepartureAt: Date? = nil,
        alightedAt: Date? = nil,
        result: BoardingResult? = nil,
        notes: String? = nil
    ) {
        self.arrivedAtStopAt = arrivedAtStopAt
        self.vehicleScheduledOrPredictedAt = vehicleScheduledOrPredictedAt
        self.vehicleActualDepartureAt = vehicleActualDepartureAt
        self.alightedAt = alightedAt
        self.result = result
        self.notes = notes
    }
}

// MARK: - GPS

public struct GpsPoint: Codable, Sendable, Equatable {
    public var recordedAt: Date
    public var lat: Double
    public var lng: Double
    public var speedMps: Double?
    public var accuracyM: Double?

    public init(recordedAt: Date, lat: Double, lng: Double, speedMps: Double? = nil, accuracyM: Double? = nil) {
        self.recordedAt = recordedAt
        self.lat = lat
        self.lng = lng
        self.speedMps = speedMps
        self.accuracyM = accuracyM
    }
}

public struct GpsTraceBatchRequest: Codable, Sendable, Equatable {
    /// 서버 상한 (`GpsTraceBatchRequest.MAX_POINTS`). 넘으면 서버가 400을 준다.
    public static let maxPoints = 500

    public var tripId: Int64?
    public var points: [GpsPoint]

    public init(tripId: Int64? = nil, points: [GpsPoint]) {
        self.tripId = tripId
        self.points = points
    }
}

/// `ignored`는 `(user, recordedAt)` 중복으로 무시된 포인트 수 — 재전송이 흡수된 것이다.
public struct GpsTraceBatchResponse: Codable, Sendable, Equatable {
    public let accepted: Int
    public let ignored: Int
}

// MARK: - 추천

public struct DepartureRecommendation: Codable, Sendable, Equatable {
    public let recommendedLeaveHomeAt: Date
    public let targetArrivalAt: Date
    public let catchProbability: Double
    public let bufferSeconds: Int
    public let modelVersion: String
    /// analytics가 이 추천을 계산한 시각.
    public let computedAt: Date
}
