import Foundation
import WhenIOffKit
import WhenIOffRecorder

@testable import WhenIOff

/// KST 2026-09-28(월) `hour:minute`.
func kst(_ hour: Int, _ minute: Int, _ second: Int = 0) -> Date {
    Date(timeIntervalSince1970: 1_790_521_200 + TimeInterval(hour * 3600 + minute * 60 + second))
}

/// 동탄 집 → (GTX-A) 동탄역 → 수서역 → 회사. WhenIOffRecorder 테스트의 표본을 줄인 것.
enum Sample {
    static let home = Coordinate(lat: 37.199, lng: 127.099)
    static let office = Coordinate(lat: 37.489, lng: 127.105)
    static let gtxLegId: Int64 = 2

    static let toWork = RecorderRoute(
        id: 1,
        direction: .toWork,
        origin: home,
        destination: office,
        transitLegs: [
            RecorderTransitLeg(
                legId: gtxLegId, mode: .gtx, lineName: "GTX-A",
                board: StopPoint(id: 4, name: "동탄", coordinate: Coordinate(lat: 37.201167, lng: 127.095111)),
                alight: StopPoint(id: 1, name: "수서", coordinate: Coordinate(lat: 37.48694, lng: 127.10194)))
        ]
    )
}

final class Clock: @unchecked Sendable {
    var now: Date
    init(_ now: Date) { self.now = now }
}

@MainActor
final class FakeLocation: LocationControlling {
    var regions: [String: MonitoredRegion] = [:]
    var started: [String] = []
    var stopped: [String] = []
    var updating = false

    var monitoredRegions: [MonitoredRegion] { Array(regions.values) }

    func startMonitoring(_ region: PlannedRegion) {
        started.append(region.identifier)
        regions[region.identifier] = MonitoredRegion(
            identifier: region.identifier, center: region.center, radius: region.radius)
    }

    func stopMonitoring(identifier: String) {
        stopped.append(identifier)
        regions[identifier] = nil
    }

    func startLocationUpdates() { updating = true }
    func stopLocationUpdates() { updating = false }
}

@MainActor
final class FakeNotifier: RecordingNotifying, DepartureAlertScheduling {
    var prompts: [Int64: BoardingPrompt] = [:]
    var tripStarts: [TripKey] = []
    var alerts: [String: LocalAlert] = [:]
    var delivered: Set<String> = []

    func showBoardingPrompt(_ prompt: BoardingPrompt) { prompts[prompt.legId] = prompt }
    func removeBoardingPrompt(legId: Int64) { prompts[legId] = nil }
    func showTripStarted(_ key: TripKey, direction: CommuteDirection) { tripStarts.append(key) }

    func pendingDepartureAlerts() async -> [LocalAlert] { Array(alerts.values) }
    func deliveredNotificationIdentifiers() async -> Set<String> { delivered }
    func schedule(_ alert: LocalAlert) async { alerts[alert.identifier] = alert }
    func cancel(_ identifiers: [String]) { for identifier in identifiers { alerts[identifier] = nil } }
}

/// 경로·메서드별로 정해 둔 응답을 돌려주고 요청을 기록한다.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [HTTPRequest] = []
    var responses: [String: HTTPResponse] = [:]

    var requests: [HTTPRequest] { lock.withLock { _requests } }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let response: HTTPResponse? = lock.withLock {
            _requests.append(request)
            return responses["\(request.method) \(request.url.path)"]
        }
        guard let response else { throw URLError(.notConnectedToInternet) }
        return response
    }

    static func json(_ status: Int, _ body: String) -> HTTPResponse {
        HTTPResponse(status: status, body: Data(body.utf8))
    }
}

func temporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("wio-tests-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}
