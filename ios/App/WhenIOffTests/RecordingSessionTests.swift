import Foundation
import Testing
import WhenIOffKit
import WhenIOffRecorder

@testable import WhenIOff

/// 어댑터가 판단 없이 번역만 하는지: 사건 → 상태기계 → 효과 실행 → 저장 → 전송.
@MainActor
@Suite("RecordingSession")
struct RecordingSessionTests {
    let directory: URL
    let clock = Clock(kst(7, 0))
    let location = FakeLocation()
    let notifier = FakeNotifier()

    init() throws {
        directory = try temporaryDirectory()
    }

    func makeSession() throws -> RecordingSession {
        let outbox = try OutboxDriver(
            store: FileOutboxStore(file: JSONFile(directory: directory, name: "outbox")),
            clock: { [clock] in clock.now })
        return try RecordingSession(
            file: JSONFile(directory: directory, name: "recording"), outbox: outbox, location: location,
            notifier: notifier, clock: { [clock] in clock.now })
    }

    func region(_ role: RegionRole, _ session: RecordingSession) -> String {
        session.snapshot.plan.regions.first { $0.roles.contains(role) }!.identifier
    }

    @Test func routesRegisterOnlyChangedRegions() throws {
        let session = try makeSession()
        session.updateRoutes([Sample.toWork], names: [1: "출근"])
        #expect(Set(location.started) == Set(session.snapshot.plan.regions.map(\.identifier)))
        #expect(location.stopped.isEmpty)

        location.started = []
        session.updateRoutes([Sample.toWork], names: [1: "출근"])
        #expect(location.started.isEmpty)
        #expect(location.stopped.isEmpty)

        session.updateRoutes([], names: [:])
        #expect(location.regions.isEmpty)
    }

    @Test func eventsBecomeOutboxCommandsAndSurviveRelaunch() throws {
        let session = try makeSession()
        session.updateRoutes([Sample.toWork], names: [1: "출근"])

        session.handle(.regionExited(region(.origin(routeId: 1), session), at: kst(7, 30)))
        #expect(location.updating)
        #expect(session.outbox.summary.pendingCount == 1)

        session.handle(.regionEntered(region(.boardStop(routeId: 1, legId: Sample.gtxLegId), session), at: kst(7, 40)))
        #expect(notifier.prompts[Sample.gtxLegId]?.attemptSeq == 1)

        session.handle(.userMissed(legId: Sample.gtxLegId, attemptSeq: 1, at: kst(7, 45), departedAt: nil, notes: nil))
        #expect(notifier.prompts[Sample.gtxLegId]?.attemptSeq == 2)

        // 앱이 죽었다가 다시 깨어나도 같은 trip을 이어서 기록한다.
        let relaunched = try makeSession()
        #expect(relaunched.state == session.state)
        #expect(relaunched.outbox.summary.pendingCount == session.outbox.summary.pendingCount)

        relaunched.handle(.userCaught(legId: Sample.gtxLegId, attemptSeq: 2, at: kst(7, 58), departedAt: nil))
        #expect(notifier.prompts[Sample.gtxLegId] == nil)
        relaunched.handle(.regionEntered(region(.destination(routeId: 1), relaunched), at: kst(8, 40)))
        #expect(!location.updating)
        #expect(relaunched.state.trip == nil)
    }

    @Test func staleCancelFromAnOldNotificationIsIgnored() throws {
        let session = try makeSession()
        session.updateRoutes([Sample.toWork], names: [1: "출근"])
        session.handle(.regionExited(region(.origin(routeId: 1), session), at: kst(7, 30)))

        session.handle(.userCancelledTrip(TripKey(routeId: 1, leftHomeAt: kst(-5, 0)), at: kst(7, 31)))
        #expect(session.state.trip != nil)

        session.handle(.userCancelledTrip(nil, at: kst(7, 32)))
        #expect(session.state.trip == nil)
        #expect(session.outbox.summary.pendingCount == 0)
    }

    @Test func outboxSendsInOrderOnceAClientIsSet() async throws {
        let session = try makeSession()
        session.updateRoutes([Sample.toWork], names: [1: "출근"])
        session.handle(.regionExited(region(.origin(routeId: 1), session), at: kst(7, 30)))
        session.handle(.regionEntered(region(.boardStop(routeId: 1, legId: Sample.gtxLegId), session), at: kst(7, 40)))
        #expect(session.outbox.summary.pendingCount == 2)

        let transport = FakeTransport()
        transport.responses = [
            "POST /api/v1/commute-trips": FakeTransport.json(
                201,
                #"{"id":31,"routeId":1,"tripDate":"2026-09-28","leftHomeAt":"2026-09-27T22:30:00Z","#
                    + #""createdAt":"2026-09-27T22:30:01Z","boardingAttempts":[]}"#),
            "POST /api/v1/commute-trips/31/boarding-attempts": FakeTransport.json(
                200,
                #"{"id":7,"tripId":31,"routeLegId":2,"attemptSeq":1,"result":"UNKNOWN","#
                    + #""createdAt":"2026-09-27T22:40:01Z"}"#),
        ]
        session.outbox.client = AppConfiguration(baseURL: URL(string: "http://wio.test"), debugAPIToken: nil)
            .client(token: "t", transport: transport)
        await session.outbox.flush()

        #expect(
            transport.requests.map(\.url.path) == [
                "/api/v1/commute-trips", "/api/v1/commute-trips/31/boarding-attempts",
            ])
        #expect(session.outbox.summary.pendingCount == 0)
        #expect(session.outbox.outbox.tripId(for: TripKey(routeId: 1, leftHomeAt: kst(7, 30))) == 31)
    }

    /// 서버에 이미 만들어진 trip을 취소하면 대기 명령은 버리고 서버 trip을 지운다 (#88).
    @Test func cancellingACreatedTripDeletesItOnTheServer() async throws {
        let session = try makeSession()
        session.updateRoutes([Sample.toWork], names: [1: "출근"])
        session.handle(.regionExited(region(.origin(routeId: 1), session), at: kst(7, 30)))

        let transport = FakeTransport()
        transport.responses = [
            "POST /api/v1/commute-trips": FakeTransport.json(
                201,
                #"{"id":31,"routeId":1,"tripDate":"2026-09-28","leftHomeAt":"2026-09-27T22:30:00Z","#
                    + #""createdAt":"2026-09-27T22:30:01Z","boardingAttempts":[]}"#),
            "DELETE /api/v1/commute-trips/31": HTTPResponse(status: 204, body: Data()),
        ]
        session.outbox.client = AppConfiguration(baseURL: URL(string: "http://wio.test"), debugAPIToken: nil)
            .client(token: "t", transport: transport)
        await session.outbox.flush()

        session.handle(.userCancelledTrip(nil, at: kst(7, 32)))
        await session.outbox.flush()

        #expect(transport.requests.map(\.method) == ["POST", "DELETE"])
        #expect(transport.requests.last?.url.path == "/api/v1/commute-trips/31")
        #expect(session.outbox.summary.pendingCount == 0)
        #expect(session.outbox.summary.deadLetters.isEmpty)
        #expect(session.outbox.outbox.tripId(for: TripKey(routeId: 1, leftHomeAt: kst(7, 30))) == nil)
    }

    @Test func offlineKeepsCommandsForRetry() async throws {
        let session = try makeSession()
        session.updateRoutes([Sample.toWork], names: [1: "출근"])
        session.handle(.regionExited(region(.origin(routeId: 1), session), at: kst(7, 30)))

        let transport = FakeTransport()  // 응답이 없으면 연결 실패
        session.outbox.client = AppConfiguration(baseURL: URL(string: "http://wio.test"), debugAPIToken: nil)
            .client(token: "t", transport: transport)
        await session.outbox.flush()

        #expect(transport.requests.count == 1)
        #expect(session.outbox.summary.pendingCount == 1)
        #expect(session.outbox.summary.nextRetryAt == kst(7, 0, 5))
    }

    @Test func departureAlertIsScheduledOnceAndNotRepeatedAfterItFired() async throws {
        let session = try makeSession()
        session.updateRoutes([Sample.toWork], names: [1: "출근"])
        let transport = FakeTransport()
        transport.responses = [
            "GET /api/v1/commute-routes/1/recommendation/latest": FakeTransport.json(
                200,
                #"{"recommendedLeaveHomeAt":"2026-09-27T23:00:00Z","targetArrivalAt":"2026-09-28T00:00:00Z","#
                    + #""catchProbability":0.91,"bufferSeconds":120,"modelVersion":"v2","#
                    + #""computedAt":"2026-09-27T20:00:00Z"}"#),
            "GET /api/v1/commute-trips": FakeTransport.json(200, "[]"),
        ]
        let client = try #require(
            AppConfiguration(baseURL: URL(string: "http://wio.test"), debugAPIToken: nil)
                .client(token: "t", transport: transport))

        let next = await session.refreshDepartureAlerts(client: client, scheduler: notifier)
        let alert = try #require(notifier.alerts["wio.departure.1.2026-09-28"])
        #expect(alert.fireAt == kst(7, 55))
        // 데이터가 없는 구간이 있어 콜드스타트 문구가 붙는다.
        #expect(alert.body.hasSuffix("아직 데이터가 적어 보수적으로 추천했어요"))
        // 목표 도착 120분 전(07:00)부터 15분마다 다시 조회한다.
        #expect(next == kst(7, 15))

        // 울린 뒤 사용자가 지워서 OS 목록에 없어도 다시 예약하지 않는다.
        notifier.alerts = [:]
        clock.now = kst(7, 56)
        await session.refreshDepartureAlerts(client: client, scheduler: notifier)
        #expect(notifier.alerts.isEmpty)
    }
}
