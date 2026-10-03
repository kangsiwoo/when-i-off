import Foundation
import UserNotifications
import WhenIOffKit
import WhenIOffRecorder

/// UserNotifications 어댑터 (ADR 0003 §7, §8).
///
/// - 탑승 알림 `WIO_BOARDING`: **탔음 / 놓쳤음** 백그라운드 액션. `userInfo`에 `legId`·`attemptSeq`를 실어
///   지난 알림의 버튼을 상태기계가 걸러내게 한다. 식별자는 구간 단위라 다음 시도가 앞 알림을 교체한다
/// - 기록 시작 확인 `WIO_TRIP_START`: 평소와 다른 방향으로 시작했을 때 **취소** 액션. trip 키를 싣는다
/// - 출발 알림: ``DepartureAlertPlanner``가 정한 식별자·시각·문구 그대로
///
/// `timeSensitive`는 Time Sensitive Notifications 권한(entitlement)이 있어야 집중 모드를 뚫는다. 없으면
/// 일반 알림으로 간다 — 엔타이틀먼트는 실기기 확인 뒤 추가한다.
@MainActor
final class NotificationService: NSObject, RecordingNotifying, DepartureAlertScheduling {
    nonisolated static let boardingCategory = "WIO_BOARDING"
    nonisolated static let tripStartCategory = "WIO_TRIP_START"
    nonisolated static let caughtAction = "WIO_CAUGHT"
    nonisolated static let missedAction = "WIO_MISSED"
    nonisolated static let cancelTripAction = "WIO_CANCEL_TRIP"
    nonisolated static let departurePrefix = "wio.departure."

    private let center = UNUserNotificationCenter.current()
    /// 알림 액션이 만든 사건. ``RecordingSession/handle(_:)``로 간다.
    var onEvents: (([RecorderEvent]) -> Void)?

    /// 앱 실행 직후(`didFinishLaunching` 안에서) 불러야 백그라운드 액션을 받는다.
    func activate() {
        center.delegate = self
        let caught = UNNotificationAction(identifier: Self.caughtAction, title: "탔음", options: [])
        let missed = UNNotificationAction(identifier: Self.missedAction, title: "놓쳤음", options: [])
        let cancel = UNNotificationAction(identifier: Self.cancelTripAction, title: "기록 취소", options: [.destructive])
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.boardingCategory, actions: [caught, missed], intentIdentifiers: [], options: []),
            UNNotificationCategory(
                identifier: Self.tripStartCategory, actions: [cancel], intentIdentifiers: [], options: []),
        ])
    }

    func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    // MARK: RecordingNotifying

    static func boardingIdentifier(legId: Int64) -> String { "wio.boarding.\(legId)" }

    func showBoardingPrompt(_ prompt: BoardingPrompt) {
        let content = UNMutableNotificationContent()
        content.title = "\(prompt.stopName) 도착"
        let line = prompt.lineName.isEmpty ? "" : "\(prompt.lineName) · "
        content.body =
            prompt.attemptSeq == 1
            ? "\(line)탔으면 \"탔음\", 보냈으면 \"놓쳤음\"을 눌러 주세요"
            : "\(line)\(prompt.attemptSeq)번째 차를 기다리는 중이에요"
        content.categoryIdentifier = Self.boardingCategory
        content.userInfo = ["legId": NSNumber(value: prompt.legId), "attemptSeq": NSNumber(value: prompt.attemptSeq)]
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        add(
            UNNotificationRequest(
                identifier: Self.boardingIdentifier(legId: prompt.legId), content: content, trigger: nil))
    }

    func removeBoardingPrompt(legId: Int64) {
        let identifier = Self.boardingIdentifier(legId: legId)
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    func showTripStarted(_ key: TripKey, direction: CommuteDirection) {
        let content = UNMutableNotificationContent()
        content.title = "\(direction.text) 기록을 시작했어요"
        content.body = "평소와 다른 시각이에요. 맞지 않으면 \"기록 취소\"를 눌러 주세요"
        content.categoryIdentifier = Self.tripStartCategory
        content.userInfo = [
            "routeId": NSNumber(value: key.routeId),
            "leftHomeAt": NSNumber(value: key.leftHomeAt.timeIntervalSince1970),
        ]
        content.sound = .default
        add(UNNotificationRequest(identifier: "wio.trip-start", content: content, trigger: nil))
    }

    // MARK: DepartureAlertScheduling

    func pendingDepartureAlerts() async -> [LocalAlert] {
        await center.pendingNotificationRequests().compactMap { request in
            guard request.identifier.hasPrefix(Self.departurePrefix),
                let fireAt = (request.trigger as? UNCalendarNotificationTrigger)?.nextTriggerDate()
            else { return nil }
            return LocalAlert(
                identifier: request.identifier, fireAt: fireAt, title: request.content.title,
                body: request.content.body)
        }
    }

    func deliveredNotificationIdentifiers() async -> Set<String> {
        Set(await center.deliveredNotifications().map(\.request.identifier))
    }

    func schedule(_ alert: LocalAlert) async {
        let content = UNMutableNotificationContent()
        content.title = alert.title
        content.body = alert.body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: alert.fireAt)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        try? await center.add(UNNotificationRequest(identifier: alert.identifier, content: content, trigger: trigger))
    }

    func cancel(_ identifiers: [String]) {
        guard !identifiers.isEmpty else { return }
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    private func add(_ request: UNNotificationRequest) {
        center.add(request) { error in
            if let error { NSLog("[wio] notification failed: %@", String(describing: error)) }
        }
    }
}

extension NotificationService: UNUserNotificationCenterDelegate {
    /// 앱이 앞에 있어도 배너를 띄운다 (정류장에서 앱을 보고 있을 수 있다).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    /// 알림 액션 → 사건. 시각은 누른 시각이다.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let now = Date()
        let info = response.notification.request.content.userInfo
        let legId = (info["legId"] as? NSNumber)?.int64Value
        let attemptSeq = (info["attemptSeq"] as? NSNumber)?.intValue
        let tripKey: TripKey? = {
            guard let routeId = (info["routeId"] as? NSNumber)?.int64Value,
                let leftHomeAt = (info["leftHomeAt"] as? NSNumber)?.doubleValue
            else { return nil }
            return TripKey(routeId: routeId, leftHomeAt: Date(timeIntervalSince1970: leftHomeAt))
        }()
        let event: RecorderEvent?
        switch response.actionIdentifier {
        case Self.caughtAction:
            event = .userCaught(legId: legId, attemptSeq: attemptSeq, at: now, departedAt: nil)
        case Self.missedAction:
            event = .userMissed(legId: legId, attemptSeq: attemptSeq, at: now, departedAt: nil, notes: nil)
        case Self.cancelTripAction:
            // 키가 없는 알림은 무엇을 취소할지 모른다 — 무시한다 (nil은 "지금 trip"이라는 뜻이라 쓰지 않는다).
            event = tripKey.map { .userCancelledTrip($0, at: now) }
        default:
            event = nil
        }
        guard let event else { return }
        await MainActor.run { onEvents?([event]) }
    }
}
