import Foundation
import WhenIOffKit
import WhenIOffRecorder

/// outbox 실행기 (ADR 0003 §5). `next → APIClient.send → complete`를 직렬로 돈다.
///
/// ADR은 "actor 하나"라고 했고, 그 actor는 메인 액터다. 기록 사건 처리(``RecordingSession``)와 같은 액터라서
/// 사건이 만든 명령이 **만든 순서대로** outbox에 들어간다 — 별도 actor로 넘기면 `await` 사이에 순서가 바뀔 수
/// 있고, 같은 trip의 기록은 순서가 곧 서버 규칙이다(#38). 네트워크 대기(`await client.send`)만 메인 밖에서 돈다.
///
/// 깨우는 때: 명령이 들어올 때(``kick()``), 포그라운드, 네트워크 복구(``expedite()``), 재시도 시각(``nextWakeAt``),
/// BGAppRefreshTask(``flush()``).
@MainActor
final class OutboxDriver {
    private(set) var outbox: Outbox<FileOutboxStore>
    /// 서버 주소나 토큰이 없으면 `nil` — 쌓기만 하고 보내지 않는다.
    var client: APIClient? {
        didSet { kick() }
    }
    var background: (any BackgroundActivity)?
    /// 상태가 바뀌었다 (화면 갱신).
    var onChange: (() -> Void)?
    /// 마지막 저장 실패. 메모리의 outbox는 계속 돌지만 앱이 죽으면 잃을 수 있어 화면에 띄운다.
    private(set) var storageError: String?

    private var drainTask: Task<Void, Never>?
    private var wakeTask: Task<Void, Never>?
    private let clock: () -> Date

    init(store: FileOutboxStore, clock: @escaping () -> Date = Date.init) throws {
        outbox = try Outbox(store: store)
        self.clock = clock
    }

    // MARK: 넣기·운영

    func enqueue(_ command: OutboxCommand) {
        record { try $0.enqueue(command, now: clock()) }
    }

    func discard(_ key: TripKey) {
        record { try $0.discard(trip: key) }
    }

    /// 토큰을 고친 뒤.
    func resume() {
        record { try $0.resume() }
        kick()
    }

    /// 네트워크가 돌아왔다: 재시도 대기를 풀고 바로 보낸다.
    func expedite() {
        record { try $0.expediteRetries() }
        kick()
    }

    func clearDeadLetters() {
        record { try $0.clearDeadLetters() }
    }

    private func record(_ change: (inout Outbox<FileOutboxStore>) throws -> Void) {
        do {
            try change(&outbox)
            storageError = nil
        } catch {
            storageError = String(describing: error)
        }
        onChange?()
    }

    // MARK: 보내기

    /// 보낼 것이 있으면 보내기 시작한다. 이미 도는 중이면 그 루프가 새 항목까지 집어 간다.
    func kick() {
        guard drainTask == nil, client != nil else { return }
        let token = background?.begin("outbox")
        drainTask = Task { [weak self] in
            await self?.drain()
            self?.drainTask = nil
            self?.scheduleWake()
            if let token { self?.background?.end(token) }
        }
    }

    /// 지금 보낼 수 있는 것을 다 보낼 때까지 기다린다 (BGAppRefreshTask).
    func flush() async {
        kick()
        await drainTask?.value
    }

    /// 다음 재시도 시각. BGAppRefreshTask의 `earliestBeginDate` 후보.
    var nextWakeAt: Date? { outbox.nextWakeAt(now: clock()) }

    private func drain() async {
        while let client, let dispatch = nextDispatch() {
            let outcome = await client.send(dispatch.request)
            record { try $0.complete(dispatch.entryId, outcome, now: clock()) }
        }
    }

    private func nextDispatch() -> OutboxDispatch? {
        var dispatch: OutboxDispatch?
        record { dispatch = try $0.next(now: clock()) }
        return dispatch
    }

    private func scheduleWake() {
        wakeTask?.cancel()
        guard let wakeAt = nextWakeAt else { return }
        let delay = max(0, wakeAt.timeIntervalSince(clock()))
        wakeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.kick()
        }
    }

    // MARK: 화면

    var summary: OutboxSummary {
        let state = outbox.state
        let busy = Set(state.entries.compactMap { $0.command.tripKey.flatMap { state.tripIds[$0] } })
        return OutboxSummary(
            pendingCount: state.entries.count,
            deadLetters: state.deadLetters,
            paused: state.paused != nil,
            nextRetryAt: nextWakeAt,
            busyTripIds: busy,
            storageError: storageError
        )
    }
}

/// 화면에 보이는 outbox 요약.
struct OutboxSummary: Equatable {
    var pendingCount = 0
    var deadLetters: [DeadLetter] = []
    /// 401·403으로 멈췄다 — 설정에서 토큰을 고쳐야 한다.
    var paused = false
    var nextRetryAt: Date?
    /// 아직 보내지 않은 명령이 있는 서버 trip. trip 상세에서 고치기를 막는다 — 고친 값을 뒤에 나가는 명령이
    /// 덮어쓸 수 있다.
    var busyTripIds: Set<Int64> = []
    var storageError: String?
}
