import Foundation
import WhenIOffKit
import WhenIOffRecorder

/// 앱이 깨어날 때마다 디스크에서 다시 읽는 기록 상태. 앱은 언제든 죽을 수 있어 사건마다 저장한다.
struct RecordingSnapshot: Codable, Equatable {
    /// 활성 경로 (서버에서 마지막으로 받은 것). 오프라인으로 깨어나도 이것으로 기록한다.
    var routes: [RecorderRoute] = []
    var routeNames: [Int64: String] = [:]
    /// 지금 OS에 걸어 둔 계획. geofence 사건의 식별자를 역할로 풀 때 **걸었던 그 계획**을 써야 한다.
    var plan = GeofencePlan()
    var recorder = RecorderState()
    /// 사용자가 고칠 거리 (기록 확인 화면). 확인하면 지운다.
    var reviews: [ReviewNote] = []
    /// 예약한 출발 알림과 울릴 시각. 울릴 시각이 지났으면 울린 것으로 본다 — 사용자가 지운 알림은 OS의
    /// delivered 목록에 남지 않아, 그것만 보면 같은 날 다시 울린다.
    var departureAlerts: [String: Date] = [:]
    var routesFetchedAt: Date?
}

/// 기록 어댑터의 중심 (ADR 0003 §1). 시스템 사건을 ``Recorder``에 넣고, 나온 ``RecorderEffect``를 실행한다.
/// 판단은 하지 않는다 — 여기에 `if`가 늘면 그 판단을 `WhenIOffRecorder`로 옮길 신호다.
///
/// 순서: 상태기계 → 효과 실행(outbox 넣기 포함) → 상태 저장 → outbox 깨우기. 기록 명령을 먼저 디스크에 둔다 —
/// 둘 사이에 죽으면 상태기계가 한 걸음 뒤로 가지만 서버로 갈 기록은 남는다.
@MainActor
final class RecordingSession {
    private(set) var snapshot: RecordingSnapshot
    private var recorder: Recorder
    private let file: JSONFile<RecordingSnapshot>
    let outbox: OutboxDriver
    private let location: any LocationControlling
    private let notifier: any RecordingNotifying
    private let planner = GeofencePlanner()
    private let alertPlanner = DepartureAlertPlanner()
    private let clock: () -> Date
    /// 상태가 바뀌었다 (화면 갱신).
    var onChange: (() -> Void)?
    private(set) var storageError: String?

    init(
        file: JSONFile<RecordingSnapshot>,
        outbox: OutboxDriver,
        location: any LocationControlling,
        notifier: any RecordingNotifying,
        clock: @escaping () -> Date = Date.init
    ) throws {
        let snapshot = try file.load() ?? RecordingSnapshot()
        self.snapshot = snapshot
        recorder = Recorder(routes: snapshot.routes, plan: snapshot.plan, state: snapshot.recorder)
        self.file = file
        self.outbox = outbox
        self.location = location
        self.notifier = notifier
        self.clock = clock
        outbox.onChange = { [weak self] in self?.onChange?() }
    }

    convenience init(
        directory: URL, location: any LocationControlling, notifier: any RecordingNotifying
    ) throws {
        let outbox = try OutboxDriver(store: FileOutboxStore(file: JSONFile(directory: directory, name: "outbox")))
        try self.init(
            file: JSONFile(directory: directory, name: "recording"), outbox: outbox, location: location,
            notifier: notifier)
    }

    var state: RecorderState { recorder.state }

    // MARK: 사건

    /// 시스템 사건 하나 (geofence, 위치, 알림 액션, 화면 버튼, tick).
    func handle(_ event: RecorderEvent) {
        perform(recorder.handle(event))
        persist()
        outbox.kick()
    }

    func handle(_ events: [RecorderEvent]) {
        guard !events.isEmpty else { return }
        for event in events { perform(recorder.handle(event)) }
        persist()
        outbox.kick()
    }

    private func perform(_ effects: [RecorderEffect]) {
        for effect in effects {
            switch effect {
            case .enqueue(let command):
                outbox.enqueue(command)
            case .startLocationUpdates:
                location.startLocationUpdates()
            case .stopLocationUpdates:
                location.stopLocationUpdates()
            case .promptBoarding(let prompt):
                notifier.showBoardingPrompt(prompt)
            case .dismissBoardingPrompt(let legId):
                notifier.removeBoardingPrompt(legId: legId)
            case .confirmTripStart(let key, let direction):
                notifier.showTripStarted(key, direction: direction)
            case .discardOutbox(let key):
                outbox.discard(key)
            case .needsReview(let note):
                snapshot.reviews.append(note)
            case .tripEnded:
                // 진행 중 trip의 경로가 주 경로였다. 끝났으니 지금 시각 기준으로 다시 고른다 (§3).
                replan()
            }
        }
    }

    // MARK: 경로·geofence

    /// 서버에서 받은 활성 경로. 진행 중인 trip은 시작할 때의 경로로 계속 기록된다 (``Recorder``가 복사해 둔다).
    func updateRoutes(_ routes: [RecorderRoute], names: [Int64: String]) {
        snapshot.routes = routes
        snapshot.routeNames = names
        snapshot.routesFetchedAt = clock()
        replan()
        persist()
    }

    /// 계획을 다시 세우고 OS 등록을 맞춘다. 바뀐 지역만 내리고 건다 (한도 때문에 내리기가 먼저).
    func replan() {
        let plan = planner.plan(routes: snapshot.routes, now: clock(), activeRouteId: recorder.state.trip?.route.id)
        recorder.update(routes: snapshot.routes, plan: plan)
        snapshot.plan = plan
        let sync = plan.sync(registered: location.monitoredRegions)
        for identifier in sync.stop { location.stopMonitoring(identifier: identifier) }
        for region in sync.start { location.startMonitoring(region) }
    }

    // MARK: 출발 알림 (ADR 0003 §8)

    /// 경로마다 최신 추천을 받아 출발 알림을 맞춘다. 다음 조회 시각(BGAppRefreshTask)을 돌려준다.
    /// 네트워크가 안 되면 그 경로의 예약은 그대로 둔다.
    @discardableResult
    func refreshDepartureAlerts(client: APIClient, scheduler: any DepartureAlertScheduling) async -> Date? {
        let now = clock()
        let pending = await scheduler.pendingDepartureAlerts()
        let delivered = await scheduler.deliveredNotificationIdentifiers()
        let firedByLedger = Set(snapshot.departureAlerts.filter { $0.value <= now }.keys)
        var nextRefresh: Date?

        for route in snapshot.routes {
            let fetched: (advice: DepartureAdvice?, trips: [CommuteTrip])
            do {
                let recommendation = try await client.latestRecommendation(routeId: route.id)
                // 콜드스타트 판정(구간별 샘플 5건)과 "그날 이미 나섰나"에 쓴다. 석 달이면 충분하다.
                let trips = try await client.trips(
                    routeId: route.id, from: LocalDate(now.addingTimeInterval(-90 * 24 * 3600)))
                fetched = (DepartureAdvice(routeId: route.id, recommendation), trips)
            } catch  where Self.isNotFound(error) {
                // 추천이 아직 없다 — 걸려 있던 알림을 지운다.
                fetched = (nil, [])
            } catch {
                continue
            }
            let (advice, trips) = fetched
            // 식별자 형식은 DepartureAlertPlanner.identifier(routeId:targetArrivalAt:) — 경로별 하나만 걸려 있다.
            let routePrefix = "wio.departure.\(route.id)."
            let current = pending.first { $0.identifier.hasPrefix(routePrefix) }
            let plan = alertPlanner.plan(
                advice: advice,
                pending: current,
                delivered: delivered.union(firedByLedger),
                alreadyLeft: advice.map {
                    DepartureAlertPlanner.alreadyLeft(for: $0, serverTrips: trips, recorder: recorder.state)
                } ?? false,
                isColdStart: advice.map {
                    ColdStart.isColdStart(
                        modelVersion: $0.modelVersion, transitLegIds: route.transitLegs.map(\.legId),
                        samplesByLeg: ColdStart.samplesByLeg(trips))
                } ?? false,
                now: now
            )
            scheduler.cancel(plan.cancel)
            for identifier in plan.cancel { snapshot.departureAlerts[identifier] = nil }
            if let alert = plan.schedule {
                await scheduler.schedule(alert)
                snapshot.departureAlerts[alert.identifier] = alert.fireAt
            }
            if let refresh = alertPlanner.nextRefreshAt(advice: advice, now: now) {
                nextRefresh = min(nextRefresh ?? refresh, refresh)
            }
        }
        // 이틀 지난 기록은 정리한다 (식별자에 날짜가 있어 다시 쓰일 일이 없다).
        snapshot.departureAlerts = snapshot.departureAlerts.filter { now.timeIntervalSince($0.value) < 2 * 24 * 3600 }
        persist()
        return nextRefresh
    }

    private static func isNotFound(_ error: any Error) -> Bool {
        (error as? APIError)?.status == 404
    }

    // MARK: 기록 확인

    func clearReviews() {
        snapshot.reviews.removeAll()
        outbox.clearDeadLetters()
        persist()
    }

    // MARK: 저장

    private func persist() {
        snapshot.recorder = recorder.state
        snapshot.plan = recorder.plan
        do {
            try file.save(snapshot)
            storageError = nil
        } catch {
            storageError = String(describing: error)
        }
        onChange?()
    }
}
