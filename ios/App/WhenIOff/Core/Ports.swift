import Foundation
import WhenIOffKit
import WhenIOffRecorder

// 시스템 프레임워크를 감싸는 경계 (CONVENTIONS: "CoreLocation 등은 프로토콜로 감싸서 테스트 가능하게").
// 구현은 Platform/ 아래에 있고, 테스트는 가짜를 넣는다. 전부 메인 액터에서 부른다.

/// 지역 감시와 trip 중 위치 갱신. 구현: `LocationService` (CoreLocation).
@MainActor
protocol LocationControlling: AnyObject {
    /// 지금 OS에 등록된 지역 (다른 앱 식별자도 섞여 있을 수 있다 — 동기화가 걸러낸다).
    var monitoredRegions: [MonitoredRegion] { get }
    func startMonitoring(_ region: PlannedRegion)
    func stopMonitoring(identifier: String)
    func startLocationUpdates()
    func stopLocationUpdates()
}

/// 기록 중 띄우는 알림. 구현: `NotificationService` (UserNotifications).
@MainActor
protocol RecordingNotifying: AnyObject {
    func showBoardingPrompt(_ prompt: BoardingPrompt)
    func removeBoardingPrompt(legId: Int64)
    /// 평소와 다른 방향의 기록 시작. 알림에 취소 액션을 단다.
    func showTripStarted(_ key: TripKey, direction: CommuteDirection)
}

/// 출발 알림 예약. 구현: `NotificationService`.
@MainActor
protocol DepartureAlertScheduling: AnyObject {
    func pendingDepartureAlerts() async -> [LocalAlert]
    func deliveredNotificationIdentifiers() async -> Set<String>
    func schedule(_ alert: LocalAlert) async
    func cancel(_ identifiers: [String])
}

/// 백그라운드에서 깨어나 보내는 동안 OS에 시간을 더 달라고 한다. 구현: `UIApplication.beginBackgroundTask`.
@MainActor
protocol BackgroundActivity: AnyObject {
    func begin(_ name: String) -> Int
    func end(_ token: Int)
}

/// API 토큰 보관. 구현: `KeychainTokenStore` (ADR 0003 §9 — Keychain이 진실).
protocol TokenStore {
    func read() throws -> String?
    func write(_ token: String?) throws
}
