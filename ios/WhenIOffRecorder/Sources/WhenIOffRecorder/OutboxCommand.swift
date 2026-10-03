import Foundation
import WhenIOffKit

/// trip의 **자연 키** `(경로, leftHomeAt)` — 서버의 재전송 흡수 키와 같다 (#37).
///
/// 앱은 서버 trip id를 몰라도 이 키로 trip을 가리킨다. id는 생성 응답(처음이면 201, 재전송이면 200)에서
/// 받아 outbox가 기억한다. 서버가 밀리초까지 비교하므로 만들 때 밀리초로 맞춘다.
public struct TripKey: Codable, Sendable, Hashable, CustomStringConvertible {
    public let routeId: Int64
    public let leftHomeAt: Date

    public init(routeId: Int64, leftHomeAt: Date) {
        self.routeId = routeId
        self.leftHomeAt = Date(timeIntervalSince1970: (leftHomeAt.timeIntervalSince1970 * 1000).rounded() / 1000)
    }

    public var description: String { "route \(routeId) @ \(Timestamp.format(leftHomeAt))" }
}

/// outbox에 쌓이는 쓰기. **모두 서버가 멱등으로 처리한다** — 같은 명령을 몇 번 보내도 결과가 같다.
///
/// - `createTrip`: `(routeId, leftHomeAt)` 재전송 흡수 (#37)
/// - `updateTrip`: PATCH, 보낸 필드만 덮어쓴다 (같은 값을 다시 써도 같다)
/// - `upsertAttempt`: `(trip, routeLegId, attemptSeq)` upsert, `nil` 필드는 건드리지 않는다 (#38).
///   하차도 PATCH(attempt id 필요) 대신 이것으로 보내 attempt id를 알 필요가 없다
/// - `uploadGps`: `(user, recordedAt)` 중복 무시
/// - `deleteTrip`: 사용자가 취소한 trip을 지운다 (#88). 이미 지웠으면 서버가 404를 주고, outbox는 그것을 성공으로 본다.
///   앱이 직접 넣지 않는다 — ``Outbox/discard(trip:now:)``가 필요할 때만 넣는다
///
/// 저장된 outbox(JSON)는 이 enum의 합성 `Codable` 형식이다. 사례를 **추가**하는 것은 이전 파일을 그대로 읽지만,
/// 기존 사례의 이름이나 연관값 이름을 바꾸면 업데이트 전 파일을 읽지 못한다.
public enum OutboxCommand: Codable, Sendable, Equatable {
    case createTrip(TripKey, tripDate: LocalDate)
    case updateTrip(TripKey, UpdateCommuteTripRequest)
    case upsertAttempt(TripKey, UpsertBoardingAttemptRequest)
    case uploadGps(TripKey?, [GpsPoint])
    case deleteTrip(TripKey)

    public var tripKey: TripKey? {
        switch self {
        case .createTrip(let key, _), .updateTrip(let key, _), .upsertAttempt(let key, _), .deleteTrip(let key):
            return key
        case .uploadGps(let key, _): return key
        }
    }

    var isDeleteTrip: Bool {
        if case .deleteTrip = self { return true }
        return false
    }

    /// 순서를 지켜야 하는 묶음. 같은 lane 안에서는 앞 명령이 끝나야 다음 명령이 나간다.
    var lane: OutboxLane {
        switch self {
        case .createTrip(let key, _), .updateTrip(let key, _), .upsertAttempt(let key, _), .deleteTrip(let key):
            return .record(key)
        case .uploadGps(let key, _): return .gps(key)
        }
    }
}

/// - `record`: trip 생성 → 탑승 시도 → 도착 (→ 취소면 삭제). 서버 순서 규칙(번호 건너뛰기 금지, 앞 시도보다 이른 출발 금지,
///   trip 범위 밖 기록 금지)을 지키려면 기록한 순서대로 도착해야 한다
/// - `gps`: GPS 배치. 순서는 상관없지만(서버가 시각으로 정렬) trip id가 필요하다. 기록 lane과 분리해
///   GPS 재시도가 탑승 기록을 막지 않게 한다
enum OutboxLane: Hashable {
    case record(TripKey)
    case gps(TripKey?)
}

/// trip id까지 풀어 바로 보낼 수 있는 요청.
public enum OutboxRequest: Sendable, Equatable {
    case createTrip(CreateCommuteTripRequest)
    case updateTrip(tripId: Int64, UpdateCommuteTripRequest)
    case upsertAttempt(tripId: Int64, UpsertBoardingAttemptRequest)
    case uploadGps(GpsTraceBatchRequest)
    case deleteTrip(tripId: Int64)
}

public struct OutboxDispatch: Sendable, Equatable {
    public var entryId: UInt64
    public var request: OutboxRequest
}

/// 요청 한 건의 결과. ``APIClient/send(_:)``가 만든다.
public enum OutboxOutcome: Sendable, Equatable {
    /// trip 생성 성공 (201 또는 재전송 200). 이 trip을 기다리던 명령이 풀린다.
    case tripCreated(id: Int64)
    case delivered
    case failed(APIError)
}

extension UpsertBoardingAttemptRequest {
    /// 같은 시도에 대한 두 upsert를 하나로. 서버가 `nil` 필드를 건드리지 않으므로 둘을 차례로 보낸 것과 같다.
    func merged(with newer: UpsertBoardingAttemptRequest) -> UpsertBoardingAttemptRequest {
        UpsertBoardingAttemptRequest(
            routeLegId: routeLegId,
            attemptSeq: newer.attemptSeq ?? attemptSeq,
            arrivedAtStopAt: newer.arrivedAtStopAt ?? arrivedAtStopAt,
            vehicleScheduledOrPredictedAt: newer.vehicleScheduledOrPredictedAt ?? vehicleScheduledOrPredictedAt,
            vehicleActualDepartureAt: newer.vehicleActualDepartureAt ?? vehicleActualDepartureAt,
            alightedAt: newer.alightedAt ?? alightedAt,
            result: newer.result ?? result,
            notes: newer.notes ?? notes
        )
    }

    var attemptKey: AttemptKey { AttemptKey(legId: routeLegId, seq: attemptSeq ?? 1) }
}

extension UpdateCommuteTripRequest {
    func merged(with newer: UpdateCommuteTripRequest) -> UpdateCommuteTripRequest {
        UpdateCommuteTripRequest(
            leftHomeAt: newer.leftHomeAt ?? leftHomeAt,
            arrivedDestinationAt: newer.arrivedDestinationAt ?? arrivedDestinationAt
        )
    }
}

struct AttemptKey: Hashable {
    var legId: Int64
    var seq: Int
}
