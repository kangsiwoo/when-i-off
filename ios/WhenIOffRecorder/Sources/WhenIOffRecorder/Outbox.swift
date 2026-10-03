import Foundation
import WhenIOffKit

/// outbox에 쌓인 명령 하나.
public struct OutboxEntry: Codable, Sendable, Equatable {
    public let id: UInt64
    public var command: OutboxCommand
    public var enqueuedAt: Date
    /// 연속 실패 횟수 (재시도 대상 실패만 센다).
    public var failures: Int
    /// 이 시각 전에는 보내지 않는다 (재시도 대기).
    public var notBefore: Date?
    /// 보냈고 결과를 아직 모른다. 앱이 다시 시작되면 풀린다 — 결과를 모르는 요청은 다시 보내도 안전하다.
    public var inFlight: Bool
}

/// 서버가 거부해 버린 명령. 기록 확인 화면에 띄워 사용자가 고치게 한다 (자동으로 다시 보내지 않는다).
public struct DeadLetter: Codable, Sendable, Equatable {
    public enum Reason: Codable, Sendable, Equatable {
        /// 서버가 4xx로 거부했다 (`status`가 `nil`이면 보내기 전에 클라이언트가 거부했거나 응답을 읽지 못했다).
        case rejected(status: Int?, message: String)
        /// 먼저 가야 할 trip 생성이 거부돼 보낼 수 없다.
        case tripRejected(TripKey)
    }

    public var command: OutboxCommand
    public var reason: Reason
    public var at: Date
}

/// 401·403: 토큰이 틀렸다. 고칠 때까지 아무것도 보내지 않는다 (지우지도 않는다).
public enum OutboxPause: String, Codable, Sendable, Equatable {
    case unauthorized
}

/// 저장소에 통째로 저장되는 outbox 상태.
public struct OutboxState: Codable, Sendable, Equatable {
    /// id 오름차순 (= 넣은 순서).
    public var entries: [OutboxEntry] = []
    /// 생성 응답으로 알게 된 trip id. trip 자연 키 → 서버 id 매핑은 이것 하나뿐이다.
    public var tripIds: [TripKey: Int64] = [:]
    public var deadLetters: [DeadLetter] = []
    public var nextId: UInt64 = 1
    public var paused: OutboxPause?

    public init() {}
}

/// outbox 상태를 보관한다. 앱은 파일(원자적 쓰기, `completeUntilFirstUserAuthentication` 보호)로,
/// 테스트는 ``InMemoryOutboxStore``로 구현한다. 변경마다 통째로 저장한다 — 항목은 많아야 수백 개다.
public protocol OutboxStore {
    func load() throws -> OutboxState?
    mutating func save(_ state: OutboxState) throws
}

public struct InMemoryOutboxStore: OutboxStore, Sendable {
    public private(set) var saved: OutboxState?
    public private(set) var saveCount = 0

    public init(_ saved: OutboxState? = nil) {
        self.saved = saved
    }

    public func load() throws -> OutboxState? { saved }

    public mutating func save(_ state: OutboxState) throws {
        saved = state
        saveCount += 1
    }
}

/// 재시도 간격과 실패 분류. 순수 함수다.
public struct RetryPolicy: Codable, Sendable, Equatable {
    public enum Decision: Sendable, Equatable {
        /// 서버에 닿지 못했거나 일시적 오류. 같은 요청을 나중에 다시 보낸다 (멱등이라 안전하다).
        case retry
        /// 다시 보내도 같은 답이 온다. 버리고 사용자에게 알린다.
        case reject
        /// 인증 실패. 전체를 멈춘다.
        case pause
        /// 2xx였으니 서버에는 반영됐다 (본문만 못 읽었다).
        case treatAsDelivered
    }

    public var initialDelay: TimeInterval
    public var maxDelay: TimeInterval

    /// 5초에서 시작해 두 배씩, 15분에서 멈춘다. 상한까지 가도 포기하지 않는다 — 지하·비행기 모드에서 몇 시간
    /// 묶여 있어도 실측 기록은 결국 올라가야 한다.
    public init(initialDelay: TimeInterval = 5, maxDelay: TimeInterval = 15 * 60) {
        self.initialDelay = initialDelay
        self.maxDelay = maxDelay
    }

    /// `failures`번 연속 실패한 뒤 기다릴 시간. 1인 사용이라 여러 클라이언트가 몰리는 일이 없어 지터는 두지 않는다.
    public func delay(afterFailures failures: Int) -> TimeInterval {
        guard failures > 0 else { return 0 }
        let exponent = Double(min(failures - 1, 30))
        return min(maxDelay, initialDelay * pow(2, exponent))
    }

    public func decide(_ error: APIError, for command: OutboxCommand) -> Decision {
        switch error {
        case .transport:
            return .retry
        case .http(let status, _, _):
            switch status {
            case 401, 403: return .pause
            case 408, 425, 429, 500...599: return .retry
            // 지우려던 trip이 없다: 앞선 삭제가 응답만 잃었거나 이미 지워졌다. 원하던 결과다 (#88).
            case 404 where command.isDeleteTrip: return .treatAsDelivered
            default: return .reject
            }
        case .decoding:
            // 생성 응답을 못 읽으면 trip id를 모르고, 다시 보내도 같은 응답이 온다(앱이 백엔드 계약보다 낡았다).
            // 나머지는 서버에 반영됐으니 성공으로 본다.
            if case .createTrip = command { return .reject }
            return .treatAsDelivered
        case .invalidRequest:
            return .reject
        }
    }
}

/// 오프라인 outbox (ADR 0003 §5).
///
/// 모든 쓰기를 서버의 자연 키로 보내므로(``OutboxCommand``) 결과를 모르는 요청은 그냥 다시 보낸다.
/// 서버 id가 필요한 곳은 trip 하나뿐이고, trip에 딸린 명령은 그 trip의 생성 명령 뒤에 줄을 서서 생성 응답의
/// id로 풀린다. 생성 응답을 잃어도 같은 생성 요청을 다시 보내면 서버가 같은 trip을 `200`으로 돌려준다.
///
/// 사용: 실행기(앱의 actor 하나)가 ``next(now:)``로 하나를 꺼내 보내고 ``complete(_:_:now:)``로 결과를 알린다.
/// 할 일이 없으면 ``nextWakeAt(now:)``까지 쉬거나 네트워크가 돌아오면 ``expediteRetries()`` 후 다시 돈다.
public struct Outbox<Store: OutboxStore> {
    public private(set) var state: OutboxState
    public private(set) var store: Store
    public var retry: RetryPolicy

    /// 저장된 상태를 읽는다. 보내던 중이던(`inFlight`) 항목은 결과를 모르므로 다시 보낼 대상으로 돌린다.
    public init(store: Store, retry: RetryPolicy = RetryPolicy()) throws {
        self.store = store
        self.retry = retry
        state = try store.load() ?? OutboxState()
        for index in state.entries.indices { state.entries[index].inFlight = false }
    }

    public var pending: [OutboxEntry] { state.entries }
    public var deadLetters: [DeadLetter] { state.deadLetters }
    public func tripId(for key: TripKey) -> Int64? { state.tripIds[key] }

    // MARK: 넣기

    /// GPS 배치는 서버 상한(500)으로 나눠 넣는다. 같은 시도에 대한 upsert와 trip PATCH는 lane의 마지막 항목이
    /// 아직 나가지 않았으면 그 항목에 합친다 (`nil` 필드를 건드리지 않는 서버 의미와 같은 결과).
    public mutating func enqueue(_ command: OutboxCommand, now: Date) throws {
        if case .uploadGps(let key, let points) = command {
            guard !points.isEmpty else { return }
            var start = points.startIndex
            while start < points.endIndex {
                let end = min(start + GpsTraceBatchRequest.maxPoints, points.endIndex)
                append(.uploadGps(key, Array(points[start..<end])), now: now)
                start = end
            }
        } else if !mergeIntoLaneTail(command) {
            append(command, now: now)
        }
        try persist()
    }

    private mutating func append(_ command: OutboxCommand, now: Date) {
        state.entries.append(
            OutboxEntry(
                id: state.nextId, command: command, enqueuedAt: now, failures: 0, notBefore: nil, inFlight: false))
        state.nextId += 1
    }

    private mutating func mergeIntoLaneTail(_ command: OutboxCommand) -> Bool {
        guard let index = state.entries.lastIndex(where: { $0.command.lane == command.lane }),
            !state.entries[index].inFlight
        else { return false }
        switch (state.entries[index].command, command) {
        case (.upsertAttempt(let key, let old), .upsertAttempt(_, let new)) where old.attemptKey == new.attemptKey:
            state.entries[index].command = .upsertAttempt(key, old.merged(with: new))
            return true
        case (.updateTrip(let key, let old), .updateTrip(_, let new)):
            state.entries[index].command = .updateTrip(key, old.merged(with: new))
            return true
        default:
            return false
        }
    }

    // MARK: 보내기

    /// 지금 보낼 수 있는 가장 오래된 명령을 꺼내 `inFlight`로 표시한다. 없으면 `nil`.
    ///
    /// lane마다 맨 앞 하나만 후보다. 맨 앞이 보내는 중이거나 재시도 대기 중이면 그 lane 전체가 기다린다.
    /// trip id가 필요한데 아직 모르면(생성 응답 전) 기다린다.
    public mutating func next(now: Date) throws -> OutboxDispatch? {
        guard state.paused == nil else { return nil }
        var blocked = Set<OutboxLane>()
        for index in state.entries.indices {
            let entry = state.entries[index]
            let lane = entry.command.lane
            guard !blocked.contains(lane) else { continue }
            blocked.insert(lane)
            if entry.inFlight { continue }
            if let notBefore = entry.notBefore, notBefore > now { continue }
            // 삭제는 그 trip의 다른 lane(GPS)까지 끝난 뒤에 나간다. 먼저 지우면 늦게 닿은 GPS가 404로 거부된다.
            if case .deleteTrip(let key) = entry.command,
                state.entries.contains(where: { $0.id != entry.id && $0.command.tripKey == key })
            {
                continue
            }
            guard let request = resolve(entry.command) else { continue }
            state.entries[index].inFlight = true
            try persist()
            return OutboxDispatch(entryId: entry.id, request: request)
        }
        return nil
    }

    private func resolve(_ command: OutboxCommand) -> OutboxRequest? {
        switch command {
        case .createTrip(let key, let tripDate):
            return .createTrip(
                CreateCommuteTripRequest(routeId: key.routeId, tripDate: tripDate, leftHomeAt: key.leftHomeAt))
        case .updateTrip(let key, let request):
            return state.tripIds[key].map { .updateTrip(tripId: $0, request) }
        case .upsertAttempt(let key, let request):
            return state.tripIds[key].map { .upsertAttempt(tripId: $0, request) }
        case .uploadGps(let key, let points):
            guard let key else { return .uploadGps(GpsTraceBatchRequest(tripId: nil, points: points)) }
            return state.tripIds[key].map { .uploadGps(GpsTraceBatchRequest(tripId: $0, points: points)) }
        case .deleteTrip(let key):
            return state.tripIds[key].map { .deleteTrip(tripId: $0) }
        }
    }

    /// 지금 보낼 것이 없을 때 다음에 깨어날 시각 (가장 이른 재시도). 기다릴 재시도가 없으면 `nil`.
    public func nextWakeAt(now: Date) -> Date? {
        guard state.paused == nil else { return nil }
        return state.entries.compactMap { entry in entry.notBefore.flatMap { $0 > now ? $0 : nil } }.min()
    }

    // MARK: 결과

    public mutating func complete(_ entryId: UInt64, _ outcome: OutboxOutcome, now: Date) throws {
        guard let index = state.entries.firstIndex(where: { $0.id == entryId }) else { return }
        let command = state.entries[index].command
        switch outcome {
        case .tripCreated(let id):
            if let key = command.tripKey, case .createTrip = command { state.tripIds[key] = id }
            state.entries.remove(at: index)
        case .delivered:
            delivered(at: index)
        case .failed(let error):
            switch retry.decide(error, for: command) {
            case .retry:
                state.entries[index].inFlight = false
                state.entries[index].failures += 1
                state.entries[index].notBefore = now.addingTimeInterval(
                    retry.delay(afterFailures: state.entries[index].failures))
            case .pause:
                state.entries[index].inFlight = false
                state.paused = .unauthorized
            case .treatAsDelivered:
                delivered(at: index)
            case .reject:
                state.entries.remove(at: index)
                state.deadLetters.append(
                    DeadLetter(
                        command: command, reason: .rejected(status: error.status, message: error.message), at: now))
                if case .createTrip(let key, _) = command { rejectDependents(of: key, now: now) }
            }
        }
        try persist()
    }

    private mutating func delivered(at index: Int) {
        let command = state.entries.remove(at: index).command
        // 지운 trip의 id는 더 쓸 일이 없다. 같은 키로 다시 만들면 새 id를 받아야 한다.
        if case .deleteTrip(let key) = command { state.tripIds[key] = nil }
    }

    /// trip 생성이 거부되면 그 trip에 딸린 명령은 영원히 보낼 수 없다. 함께 버리고 알린다.
    /// 삭제 명령은 알리지 않는다 — 서버에 trip이 없으니 지울 것도 없다.
    private mutating func rejectDependents(of key: TripKey, now: Date) {
        let dependents = state.entries.filter { $0.command.tripKey == key && !$0.command.isDeleteTrip }
        state.entries.removeAll { $0.command.tripKey == key }
        state.deadLetters += dependents.map { DeadLetter(command: $0.command, reason: .tripRejected(key), at: now) }
    }

    // MARK: 운영

    /// 토큰을 고친 뒤 다시 보내기 시작한다.
    public mutating func resume() throws {
        state.paused = nil
        try persist()
    }

    /// 네트워크가 돌아왔을 때: 재시도 대기를 풀어 바로 보낸다 (실패 횟수는 유지).
    public mutating func expediteRetries() throws {
        for index in state.entries.indices { state.entries[index].notBefore = nil }
        try persist()
    }

    /// 사용자가 기록을 취소한 trip (#88). 아직 보내지 않은 명령은 버리고, 서버에 trip이 있을 수 있으면 지우는 명령을
    /// 넣는다.
    ///
    /// - 보내는 중(`inFlight`)인 명령은 결과를 기다린다. 삭제는 record lane 맨 뒤에 서고, 다른 lane(GPS)의 보내는 중인
    ///   명령까지 끝난 뒤에 나간다(``next(now:)``) — 먼저 지우면 늦게 닿은 기록이 404로 거부되거나 trip 없이 남는다
    /// - 서버에 trip이 있을 수 있는 때: 생성 응답으로 id를 알거나, 생성 요청이 이미 한 번 나갔다(보내는 중이거나
    ///   결과를 모른 채 재시도 대기). 뒤의 경우 생성 명령을 버리지 않는다 — 다시 보내면 서버가 같은 trip의 id를
    ///   돌려주고(#37), 그 id로 지운다. 서버가 그때 처음 만들었더라도 바로 지워지므로 결과는 같다
    /// - 생성이 한 번도 나가지 않았으면 서버에 아무것도 없다. 버리기만 한다
    public mutating func discard(trip key: TripKey, now: Date) throws {
        let createMayHaveLanded = state.entries.contains { entry in
            guard case .createTrip(key, _) = entry.command else { return false }
            return entry.inFlight || entry.failures > 0
        }
        let deleting = state.entries.contains { $0.command == .deleteTrip(key) }
        state.entries.removeAll { entry in
            guard entry.command.tripKey == key, !entry.inFlight else { return false }
            switch entry.command {
            case .createTrip: return !createMayHaveLanded
            case .deleteTrip: return false
            default: return true
            }
        }
        if !deleting, state.tripIds[key] != nil || createMayHaveLanded {
            append(.deleteTrip(key), now: now)
        }
        try persist()
    }

    /// 기록 확인 화면에서 확인한 거부 기록을 지운다.
    public mutating func clearDeadLetters() throws {
        state.deadLetters.removeAll()
        try persist()
    }

    private mutating func persist() throws {
        try store.save(state)
    }
}
