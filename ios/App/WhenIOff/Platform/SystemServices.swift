import BackgroundTasks
import Foundation
import Network
import UIKit

/// `NWPathMonitor`: 네트워크가 돌아오면 outbox 재시도 대기를 풀어 바로 보낸다 (ADR 0003 §5).
@MainActor
final class NetworkMonitor {
    private let monitor = NWPathMonitor()
    private var wasSatisfied = true
    var onRestored: (() -> Void)?

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in self?.update(satisfied: satisfied) }
        }
        monitor.start(queue: DispatchQueue(label: "wio.network"))
    }

    private func update(satisfied: Bool) {
        defer { wasSatisfied = satisfied }
        if satisfied, !wasSatisfied { onRestored?() }
    }
}

/// 백그라운드에서 깨어나 outbox를 보내는 동안 OS에 시간을 더 달라고 한다.
@MainActor
final class UIKitBackgroundActivity: BackgroundActivity {
    func begin(_ name: String) -> Int {
        var identifier = UIBackgroundTaskIdentifier.invalid
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) {
            UIApplication.shared.endBackgroundTask(identifier)
        }
        return identifier.rawValue
    }

    func end(_ token: Int) {
        UIApplication.shared.endBackgroundTask(UIBackgroundTaskIdentifier(rawValue: token))
    }
}

/// BGAppRefreshTask (ADR 0003 §5, §8): 경로·추천 재조회, 출발 알림 갱신, outbox 깨우기.
enum BackgroundRefresh {
    /// Info.plist `BGTaskSchedulerPermittedIdentifiers`와 같아야 한다.
    static let identifier = "com.kangsiwoo.whenioff.refresh"

    /// `didFinishLaunching`이 끝나기 전에 불러야 한다.
    static func register(_ work: @escaping @MainActor () async -> Date?) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            let job = Task { @MainActor in
                let next = await work()
                schedule(at: next)
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    /// `date`가 없으면 예약하지 않는다 (다음 조회는 앱을 열 때).
    static func schedule(at date: Date?) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        guard let date else { return }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = date
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // 시뮬레이터는 BGTaskScheduler를 지원하지 않아 여기로 온다.
            NSLog("[wio] background refresh not scheduled: %@", String(describing: error))
        }
    }
}
