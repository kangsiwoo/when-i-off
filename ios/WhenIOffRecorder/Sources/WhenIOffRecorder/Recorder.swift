import Foundation
import WhenIOffKit

// MARK: - 입력

/// 위치 한 점 (CoreLocation `CLLocation`에서 어댑터가 옮긴다).
public struct LocationSample: Codable, Sendable, Equatable {
    public var recordedAt: Date
    public var coordinate: Coordinate
    /// 수평 정확도(m). 음수·`nil`은 위치가 무효하다는 뜻이다.
    public var accuracyM: Double?
    public var speedMps: Double?

    public init(recordedAt: Date, coordinate: Coordinate, accuracyM: Double?, speedMps: Double? = nil) {
        self.recordedAt = recordedAt
        self.coordinate = coordinate
        self.accuracyM = accuracyM
        self.speedMps = speedMps
    }
}

/// 상태기계에 들어가는 사건. 시각은 **사건이 일어난 시각**이다 (geofence는 OS가 준 시각 — `CLMonitor.Event.date`,
/// 그것이 없는 `CLLocationManager` 어댑터는 전달된 시각 — 버튼은 누른 시각).
public enum RecorderEvent: Sendable, Equatable {
    case regionExited(String, at: Date)
    case regionEntered(String, at: Date)
    /// 탔음. `legId`/`attemptSeq`는 알림에 실어 둔 값이다 — 지금 기다리는 시도와 다르면 지난 알림을 누른 것이라
    /// 무시한다. `nil`이면(앱 화면의 버튼) 지금 기다리는 시도에 적용한다. `departedAt`이 없으면 누른 시각.
    case userCaught(legId: Int64?, attemptSeq: Int?, at: Date, departedAt: Date?)
    /// 놓쳤음. 같은 구간의 다음 차는 `attemptSeq` + 1로 기록한다 (#38).
    case userMissed(legId: Int64?, attemptSeq: Int?, at: Date, departedAt: Date?, notes: String?)
    /// 평소와 다른 시각에 시작된 기록을 사용자가 취소했다. `trip`은 알림에 실어 둔 키다 — 지금 기록 중인 trip과
    /// 다르면 지난 알림을 누른 것이라 무시한다. `nil`이면(앱 화면의 버튼) 지금 trip을 취소한다.
    case userCancelledTrip(TripKey?, at: Date)
    case location(LocationSample)
    /// 주기 신호 (GPS 묶음 업로드, 오래된 trip 정리). 앱이 깨어 있을 때 30초마다, 그리고 깨어날 때마다.
    case tick(Date)
}

// MARK: - 출력

/// 탔음/놓쳤음 알림에 띄울 내용. 알림 식별자는 `legId`로 정해 같은 구간의 알림을 교체한다.
public struct BoardingPrompt: Codable, Sendable, Equatable {
    public var tripKey: TripKey
    public var legId: Int64
    public var attemptSeq: Int
    public var stopName: String
    public var lineName: String
}

/// 자동 기록이 확신하지 못한 것. trip 상세 화면에서 사용자가 고칠 후보로 띄운다.
public struct ReviewNote: Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        /// 집 이탈을 놓쳐 첫 정류장 진입 시각을 `leftHomeAt`으로 썼다
        case leftHomeApproximated
        /// 승차 정류장 진입 없이 하차 정류장에 들어왔다 (지하 GPS 유실)
        case boardStopMissed
        /// 탔음/놓쳤음 입력 없이 다음 지점에 도착해 "탔음"으로 기록했다. 출발 시각은 비어 있다
        case caughtInferred
        /// 탔지만 하차 정류장 진입이 없었다. `alightedAt`이 비어 있다
        case alightMissed
        /// 지점 진입이 하나도 없이 지나간 구간
        case legSkipped
        /// 시각이 서버 규칙(#37, #38)에 어긋나 그 값을 보내지 않았다
        case timeOutOfOrder
        /// 같은 구간 시도 20번 상한
        case attemptLimitReached
        /// 집으로 돌아와 머물러 기록을 접었다 (서버에는 `leftHomeAt`만 있는 trip이 남는다)
        case tripAbandoned
        /// 도착 없이 너무 오래 지나 기록을 접었다
        case tripTimedOut
        /// 평소 방향이 아닌 시각에 시작했다
        case unusualDirection
    }

    public var tripKey: TripKey
    public var legId: Int64?
    public var kind: Kind
    public var at: Date
}

/// 상태기계가 앱(어댑터)에 시키는 일.
public enum RecorderEffect: Sendable, Equatable {
    case enqueue(OutboxCommand)
    case startLocationUpdates
    case stopLocationUpdates
    case promptBoarding(BoardingPrompt)
    case dismissBoardingPrompt(legId: Int64)
    /// 평소 방향이 아닌 시각의 시작. "퇴근 기록을 시작했어요 — 아니면 취소" 알림을 띄운다.
    case confirmTripStart(TripKey, CommuteDirection)
    /// outbox에서 이 trip의 대기 명령을 버린다 (``Outbox/discard(trip:)``).
    case discardOutbox(TripKey)
    case needsReview(ReviewNote)
    /// trip이 끝났다. 어댑터는 geofence 계획을 다시 세운다.
    case tripEnded(TripKey)
}

// MARK: - 상태

public struct RecorderPolicy: Codable, Sendable, Equatable {
    /// 이보다 부정확한 점은 버린다 (#4).
    public var maxAccuracyM: Double = 100
    /// 이만큼 모이면 올린다.
    public var gpsBatchSize: Int = 100
    /// 첫 점이 쌓인 뒤 이만큼 지나면 올린다.
    public var gpsBatchInterval: TimeInterval = 30
    /// 출발 뒤 첫 정류장 전에 집으로 돌아와 이만큼 머물면 기록을 접는다.
    public var abandonAfterReturnHome: TimeInterval = 30 * 60
    /// 도착 없이 이만큼 지나면 기록을 접는다.
    public var maxTripDuration: TimeInterval = 4 * 60 * 60
    /// 기록이 끝난 경로의 정류장 진입을 이 시간 동안은 새 trip으로 보지 않는다 (늦게 온 geofence 이벤트).
    public var quietAfterTripEnd: TimeInterval = 60 * 60
    /// 서버 상한 (`attemptSeq` 1~20).
    public var maxAttemptSeq: Int = 20

    public init() {}
}

/// 구간 하나의 진행.
public struct LegProgress: Codable, Sendable, Equatable {
    public enum Phase: String, Codable, Sendable {
        case notStarted
        /// 승차 정류장에서 차를 기다린다 (탔음/놓쳤음 대기)
        case waiting
        case riding
        case done
    }

    public var legId: Int64
    public var phase: Phase = .notStarted
    /// 지금(또는 마지막으로) 기다리는 시도 번호.
    public var attemptSeq: Int = 1
    /// 지금 시도의 `vehicleActualDepartureAt` (탔음).
    public var departureAt: Date?
    /// 앞 시도들의 출발 시각 중 가장 늦은 것. 뒤 시도의 출발은 이보다 이를 수 없다 (#38).
    public var lastMissedDepartureAt: Date?
}

public struct ActiveTrip: Codable, Sendable, Equatable {
    public var key: TripKey
    public var route: RecorderRoute
    /// 서버의 현재 `leftHomeAt`. 다시 나서면 `key.leftHomeAt`과 달라진다 (키는 그대로).
    public var leftHomeAt: Date
    public var legs: [LegProgress]
    /// 첫 정류장 전에 집(출발지)으로 다시 들어온 시각.
    public var returnedToOriginAt: Date?
    /// 보낸 기록 시각 중 가장 늦은 것. 도착 시각은 이보다 이를 수 없다 (#37).
    public var latestRecordedAt: Date
    public var gpsBuffer: [GpsPoint] = []

    var anyLegStarted: Bool { legs.contains { $0.phase != .notStarted } }
}

public struct RecorderState: Codable, Sendable, Equatable {
    public var trip: ActiveTrip?
    /// 경로별 마지막 trip 종료 시각.
    public var lastTripEndedAt: [Int64: Date] = [:]

    public init() {}
}

// MARK: - 상태기계

/// 기록 상태기계 (ADR 0003 §4). 사건을 받아 상태를 바꾸고 할 일(``RecorderEffect``)을 돌려준다. I/O가 없다.
///
/// 지켜야 하는 서버 규칙과 여기서의 처리:
/// - **trip 범위 (#37)**: 모든 시도 시각은 `[leftHomeAt, arrivedDestinationAt]` 안. `leftHomeAt`보다 이른 사건은
///   버리고, 도착은 이미 보낸 기록보다 이를 수 없으며, 도착 뒤의 사건은 trip이 끝났으니 받지 않는다
/// - **시도 번호·순서 (#38, #42)**: 번호는 놓쳤음마다 1씩만 오르고, 뒤 시도의 출발이 앞 시도보다 이르면 그 값을
///   보내지 않는다. 뒤 시도에는 `arrivedAtStopAt`을 싣지 않는다
/// - **하차 ≥ 출발**: 어기면 하차 시각을 보내지 않는다
///
/// 어긴 값을 그럴듯하게 고쳐 보내지 않는다. 빼고 보내고 ``ReviewNote``로 알린다 — 실측 기록은 지어내지 않는다.
public struct Recorder: Sendable {
    public private(set) var state: RecorderState
    public private(set) var routes: [RecorderRoute]
    public private(set) var plan: GeofencePlan
    public var policy: RecorderPolicy
    public var direction: DirectionPolicy

    public init(
        routes: [RecorderRoute],
        plan: GeofencePlan,
        state: RecorderState = RecorderState(),
        policy: RecorderPolicy = RecorderPolicy(),
        direction: DirectionPolicy = DirectionPolicy()
    ) {
        self.routes = routes
        self.plan = plan
        self.state = state
        self.policy = policy
        self.direction = direction
    }

    /// 경로나 geofence 계획이 바뀌었을 때. 진행 중인 trip은 시작할 때의 경로로 계속 기록한다.
    public mutating func update(routes: [RecorderRoute], plan: GeofencePlan) {
        self.routes = routes
        self.plan = plan
    }

    public mutating func handle(_ event: RecorderEvent) -> [RecorderEffect] {
        var effects: [RecorderEffect] = []
        switch event {
        case .regionExited(let identifier, let at):
            regionExited(plan.roles(for: identifier), at: at, &effects)
        case .regionEntered(let identifier, let at):
            regionEntered(plan.roles(for: identifier), at: at, &effects)
        case .userCaught(let legId, let attemptSeq, let at, let departedAt):
            userAnswered(legId: legId, attemptSeq: attemptSeq, departedAt: departedAt ?? at, missed: nil, &effects)
        case .userMissed(let legId, let attemptSeq, let at, let departedAt, let notes):
            userAnswered(
                legId: legId, attemptSeq: attemptSeq, departedAt: departedAt ?? at, missed: notes ?? "", &effects)
        case .userCancelledTrip(let key, _):
            if key == nil || key == state.trip?.key { cancelTrip(&effects) }
        case .location(let sample):
            location(sample, &effects)
        case .tick(let now):
            tick(now, &effects)
        }
        return effects
    }

    // MARK: 지역

    private mutating func regionExited(_ roles: [RegionRole], at: Date, _ effects: inout [RecorderEffect]) {
        guard var trip = state.trip else {
            guard let start = direction.routeToStart(exiting: roles, routes: routes, at: at) else { return }
            startTrip(start.route, leftHomeAt: at, &effects)
            if start.outsideUsualHours, let key = state.trip?.key {
                effects.append(.confirmTripStart(key, start.route.direction))
                effects.append(.needsReview(ReviewNote(tripKey: key, legId: nil, kind: .unusualDirection, at: at)))
            }
            return
        }
        // 첫 정류장 전에 집에 들렀다가 다시 나섰다: 실제 출발은 지금이다. 아직 시도 기록이 없으니 `leftHomeAt`을
        // 늦춰도 범위 규칙(#37)에 걸릴 것이 없다. trip 키는 처음 값 그대로 둔다 (생성 재전송이 같은 trip을 찾도록).
        guard roles.contains(.origin(routeId: trip.route.id)), trip.returnedToOriginAt != nil, !trip.anyLegStarted,
            at > trip.leftHomeAt
        else { return }
        trip.returnedToOriginAt = nil
        trip.leftHomeAt = at
        trip.latestRecordedAt = at
        state.trip = trip
        effects.append(.enqueue(.updateTrip(trip.key, UpdateCommuteTripRequest(leftHomeAt: at))))
    }

    private mutating func regionEntered(_ roles: [RegionRole], at: Date, _ effects: inout [RecorderEffect]) {
        if state.trip == nil { startTripFromFirstStop(roles, at: at, &effects) }
        guard let trip = state.trip else { return }
        let route = trip.route
        let relevant = roles.filter { $0.routeId == route.id }

        if relevant.contains(.origin(routeId: route.id)), !trip.anyLegStarted {
            let returned = trip.returnedToOriginAt ?? at
            state.trip?.returnedToOriginAt = returned
        }
        // 환승역처럼 한 지역이 "앞 구간 하차"와 "다음 구간 승차"를 같이 가지면 구간 순서대로 처리한다.
        let legRoles = relevant.compactMap { role -> (index: Int, alight: Bool)? in
            switch role {
            case .boardStop(_, let legId): return route.legIndex(of: legId).map { ($0, false) }
            case .alightStop(_, let legId): return route.legIndex(of: legId).map { ($0, true) }
            default: return nil
            }
        }.sorted { ($0.index, $0.alight ? 1 : 0) < ($1.index, $1.alight ? 1 : 0) }
        for role in legRoles {
            guard state.trip != nil else { return }
            if role.alight {
                enterAlightStop(role.index, at: at, &effects)
            } else {
                enterBoardStop(role.index, at: at, &effects)
            }
        }
        if relevant.contains(.destination(routeId: route.id)), state.trip != nil {
            arrive(at: at, &effects)
        }
    }

    /// 집 이탈을 놓친 채(앱이 꺼져 있었거나 geofence가 늦었다) 첫 구간 승차 정류장에 들어왔다. 그 시각을
    /// `leftHomeAt`으로 trip을 시작한다 — 생성의 멱등 키가 필요하고, 범위 규칙상 정류장 도착보다 늦을 수 없다.
    /// 평소 방향인 경로만, 그 경로의 기록이 막 끝난 직후가 아닐 때만 (늦게 온 이벤트로 trip을 새로 만들지 않게).
    private mutating func startTripFromFirstStop(_ roles: [RegionRole], at: Date, _ effects: inout [RecorderEffect]) {
        let usual = direction.usualDirection(at: at)
        let candidates = direction.prioritized(routes, at: at).filter { route in
            guard route.direction == usual, let first = route.transitLegs.first,
                roles.contains(.boardStop(routeId: route.id, legId: first.legId))
            else { return false }
            if let ended = state.lastTripEndedAt[route.id], at < ended.addingTimeInterval(policy.quietAfterTripEnd) {
                return false
            }
            return true
        }
        guard let route = candidates.first else { return }
        startTrip(route, leftHomeAt: at, &effects)
        if let key = state.trip?.key {
            effects.append(.needsReview(ReviewNote(tripKey: key, legId: nil, kind: .leftHomeApproximated, at: at)))
        }
    }

    private mutating func startTrip(_ route: RecorderRoute, leftHomeAt at: Date, _ effects: inout [RecorderEffect]) {
        let key = TripKey(routeId: route.id, leftHomeAt: at)
        state.trip = ActiveTrip(
            key: key,
            route: route,
            leftHomeAt: key.leftHomeAt,
            legs: route.transitLegs.map { LegProgress(legId: $0.legId) },
            latestRecordedAt: key.leftHomeAt
        )
        // tripDate는 KST 달력 날짜다 (UTC로 뽑으면 출근이 전날로 기록된다).
        effects.append(.enqueue(.createTrip(key, tripDate: LocalDate(key.leftHomeAt))))
        effects.append(.startLocationUpdates)
    }

    // MARK: 구간

    private mutating func enterBoardStop(_ index: Int, at: Date, _ effects: inout [RecorderEffect]) {
        guard var trip = state.trip, trip.legs[index].phase == .notStarted else { return }
        guard at >= trip.leftHomeAt else {
            return review(.timeOutOfOrder, legId: trip.legs[index].legId, at: at, &effects)
        }
        closeEarlierLegs(before: index, at: at, &trip, &effects)
        let leg = trip.route.transitLegs[index]
        trip.legs[index].phase = .waiting
        trip.returnedToOriginAt = nil
        trip.latestRecordedAt = max(trip.latestRecordedAt, at)
        state.trip = trip
        effects.append(
            .enqueue(
                .upsertAttempt(
                    trip.key, UpsertBoardingAttemptRequest(routeLegId: leg.legId, attemptSeq: 1, arrivedAtStopAt: at))))
        effects.append(.promptBoarding(prompt(trip, index)))
    }

    private mutating func enterAlightStop(_ index: Int, at: Date, _ effects: inout [RecorderEffect]) {
        guard var trip = state.trip, trip.legs[index].phase != .done else { return }
        let legId = trip.legs[index].legId
        guard at >= trip.leftHomeAt else { return review(.timeOutOfOrder, legId: legId, at: at, &effects) }
        closeEarlierLegs(before: index, at: at, &trip, &effects)
        var progress = trip.legs[index]
        var request = UpsertBoardingAttemptRequest(routeLegId: legId, attemptSeq: progress.attemptSeq, alightedAt: at)
        switch progress.phase {
        case .notStarted:
            request.result = .caught
            effects.append(.needsReview(ReviewNote(tripKey: trip.key, legId: legId, kind: .boardStopMissed, at: at)))
        case .waiting:
            request.result = .caught
            effects.append(.dismissBoardingPrompt(legId: legId))
            effects.append(.needsReview(ReviewNote(tripKey: trip.key, legId: legId, kind: .caughtInferred, at: at)))
        case .riding:
            if let departure = progress.departureAt, at < departure {
                request.alightedAt = nil
                effects.append(.needsReview(ReviewNote(tripKey: trip.key, legId: legId, kind: .timeOutOfOrder, at: at)))
            }
        case .done:
            return
        }
        progress.phase = .done
        trip.legs[index] = progress
        trip.returnedToOriginAt = nil
        if request.alightedAt != nil { trip.latestRecordedAt = max(trip.latestRecordedAt, at) }
        state.trip = trip
        if request.alightedAt != nil || request.result != nil {
            effects.append(.enqueue(.upsertAttempt(trip.key, request)))
        }
    }

    /// 뒤 구간에 도착했으니 앞 구간은 끝났다. 기다리던 구간은 (무엇이든) 탄 것이다.
    private func closeEarlierLegs(
        before index: Int, at: Date, _ trip: inout ActiveTrip, _ effects: inout [RecorderEffect]
    ) {
        for earlier in 0..<index {
            let legId = trip.legs[earlier].legId
            switch trip.legs[earlier].phase {
            case .notStarted:
                effects.append(.needsReview(ReviewNote(tripKey: trip.key, legId: legId, kind: .legSkipped, at: at)))
            case .waiting:
                effects.append(.dismissBoardingPrompt(legId: legId))
                effects.append(
                    .enqueue(
                        .upsertAttempt(
                            trip.key,
                            UpsertBoardingAttemptRequest(
                                routeLegId: legId, attemptSeq: trip.legs[earlier].attemptSeq, result: .caught))))
                effects.append(.needsReview(ReviewNote(tripKey: trip.key, legId: legId, kind: .caughtInferred, at: at)))
            case .riding:
                effects.append(.needsReview(ReviewNote(tripKey: trip.key, legId: legId, kind: .alightMissed, at: at)))
            case .done:
                continue
            }
            trip.legs[earlier].phase = .done
        }
    }

    // MARK: 버튼

    /// `missed`가 `nil`이면 탔음, 아니면 놓쳤음(값은 메모, 빈 문자열이면 메모 없음).
    private mutating func userAnswered(
        legId: Int64?,
        attemptSeq: Int?,
        departedAt: Date,
        missed notes: String?,
        _ effects: inout [RecorderEffect]
    ) {
        guard var trip = state.trip else { return }
        // 기다리는 구간이 없으면(승차 정류장 진입을 놓쳤다) 아직 시작하지 않은 첫 구간에 적용한다.
        // 타고 있는 구간이 있으면 그렇게 하지 않는다 — 같은 버튼을 두 번 누른 것이다.
        let riding = trip.legs.contains { $0.phase == .riding }
        guard
            let index = trip.legs.firstIndex(where: { $0.phase == .waiting })
                ?? (riding ? nil : trip.legs.firstIndex(where: { $0.phase == .notStarted }))
        else { return }
        var progress = trip.legs[index]
        // 지난 알림(다른 구간, 이미 끝난 시도)의 버튼이면 무시한다.
        if let legId, legId != progress.legId { return }
        if let attemptSeq, attemptSeq != progress.attemptSeq { return }
        if progress.phase == .notStarted { closeEarlierLegs(before: index, at: departedAt, &trip, &effects) }

        var request = UpsertBoardingAttemptRequest(
            routeLegId: progress.legId, attemptSeq: progress.attemptSeq, result: notes == nil ? .caught : .missed)
        if let notes, !notes.isEmpty { request.notes = notes }
        let tooEarly =
            departedAt < trip.leftHomeAt || progress.lastMissedDepartureAt.map { departedAt < $0 } ?? false
        if tooEarly {
            effects.append(
                .needsReview(
                    ReviewNote(tripKey: trip.key, legId: progress.legId, kind: .timeOutOfOrder, at: departedAt)))
        } else {
            request.vehicleActualDepartureAt = departedAt
            trip.latestRecordedAt = max(trip.latestRecordedAt, departedAt)
        }
        effects.append(.enqueue(.upsertAttempt(trip.key, request)))

        if notes == nil {
            progress.phase = .riding
            progress.departureAt = request.vehicleActualDepartureAt
            effects.append(.dismissBoardingPrompt(legId: progress.legId))
        } else if progress.attemptSeq >= policy.maxAttemptSeq {
            progress.phase = .done
            effects.append(.dismissBoardingPrompt(legId: progress.legId))
            effects.append(
                .needsReview(
                    ReviewNote(tripKey: trip.key, legId: progress.legId, kind: .attemptLimitReached, at: departedAt)))
        } else {
            // 다음 차. 뒤 시도에는 정류장 도착 시각을 싣지 않는다 (#42) — 서버 스냅샷은 앞 시도의 출발 시각으로 채운다.
            progress.phase = .waiting
            if let departure = request.vehicleActualDepartureAt {
                progress.lastMissedDepartureAt = max(progress.lastMissedDepartureAt ?? departure, departure)
            }
            progress.attemptSeq += 1
        }
        trip.legs[index] = progress
        trip.returnedToOriginAt = nil
        state.trip = trip
        if progress.phase == .waiting { effects.append(.promptBoarding(prompt(trip, index))) }
    }

    // MARK: 끝

    private mutating func arrive(at: Date, _ effects: inout [RecorderEffect]) {
        guard var trip = state.trip else { return }
        guard at >= trip.latestRecordedAt else {
            // 이미 보낸 기록보다 이른 도착은 서버가 거부한다(#37). 도착을 빼고 끝낸다.
            review(.timeOutOfOrder, legId: nil, at: at, &effects)
            return endTrip(at: at, &effects)
        }
        closeEarlierLegs(before: trip.legs.count, at: at, &trip, &effects)
        state.trip = trip
        effects.append(.enqueue(.updateTrip(trip.key, UpdateCommuteTripRequest(arrivedDestinationAt: at))))
        endTrip(at: at, &effects)
    }

    private mutating func endTrip(at: Date, _ effects: inout [RecorderEffect]) {
        guard let trip = state.trip else { return }
        flushGps(&effects)
        for leg in trip.legs where leg.phase == .waiting { effects.append(.dismissBoardingPrompt(legId: leg.legId)) }
        effects.append(.stopLocationUpdates)
        effects.append(.tripEnded(trip.key))
        state.lastTripEndedAt[trip.route.id] = at
        state.trip = nil
    }

    private mutating func cancelTrip(_ effects: inout [RecorderEffect]) {
        guard let trip = state.trip else { return }
        for leg in trip.legs where leg.phase == .waiting { effects.append(.dismissBoardingPrompt(legId: leg.legId)) }
        effects.append(.discardOutbox(trip.key))
        effects.append(.stopLocationUpdates)
        effects.append(.tripEnded(trip.key))
        state.trip = nil
    }

    private mutating func tick(_ now: Date, _ effects: inout [RecorderEffect]) {
        guard let trip = state.trip else { return }
        if let returned = trip.returnedToOriginAt, now.timeIntervalSince(returned) >= policy.abandonAfterReturnHome {
            review(.tripAbandoned, legId: nil, at: now, &effects)
            return endTrip(at: now, &effects)
        }
        if now.timeIntervalSince(trip.leftHomeAt) >= policy.maxTripDuration {
            var closing = trip
            closeEarlierLegs(before: closing.legs.count, at: now, &closing, &effects)
            state.trip = closing
            review(.tripTimedOut, legId: nil, at: now, &effects)
            return endTrip(at: now, &effects)
        }
        if let first = trip.gpsBuffer.first, now.timeIntervalSince(first.recordedAt) >= policy.gpsBatchInterval {
            flushGps(&effects)
        }
    }

    // MARK: GPS

    private mutating func location(_ sample: LocationSample, _ effects: inout [RecorderEffect]) {
        guard var trip = state.trip else { return }
        guard let accuracy = sample.accuracyM, accuracy >= 0, accuracy <= policy.maxAccuracyM else { return }
        trip.gpsBuffer.append(
            GpsPoint(
                recordedAt: sample.recordedAt,
                lat: sample.coordinate.lat,
                lng: sample.coordinate.lng,
                speedMps: sample.speedMps.flatMap { $0 >= 0 ? $0 : nil },
                accuracyM: accuracy
            ))
        state.trip = trip
        let first = trip.gpsBuffer[0].recordedAt
        if trip.gpsBuffer.count >= policy.gpsBatchSize
            || sample.recordedAt.timeIntervalSince(first) >= policy.gpsBatchInterval
        {
            flushGps(&effects)
        }
    }

    private mutating func flushGps(_ effects: inout [RecorderEffect]) {
        guard let trip = state.trip, !trip.gpsBuffer.isEmpty else { return }
        effects.append(.enqueue(.uploadGps(trip.key, trip.gpsBuffer)))
        state.trip?.gpsBuffer = []
    }

    // MARK: 도움

    private func prompt(_ trip: ActiveTrip, _ index: Int) -> BoardingPrompt {
        let leg = trip.route.transitLegs[index]
        return BoardingPrompt(
            tripKey: trip.key,
            legId: leg.legId,
            attemptSeq: trip.legs[index].attemptSeq,
            stopName: leg.board.name,
            lineName: leg.lineName
        )
    }

    private func review(_ kind: ReviewNote.Kind, legId: Int64?, at: Date, _ effects: inout [RecorderEffect]) {
        guard let trip = state.trip else { return }
        effects.append(.needsReview(ReviewNote(tripKey: trip.key, legId: legId, kind: kind, at: at)))
    }
}
