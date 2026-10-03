import CoreLocation
import Foundation
import SwiftUI
import UserNotifications
import WhenIOffKit
import WhenIOffRecorder

/// 앱 전체의 연결 지점. 시스템 어댑터를 ``RecordingSession``에 잇고, 화면에 상태를 내보낸다.
///
/// 앱은 대부분 꺼져 있다가 지역 사건·알림 액션·BGAppRefreshTask로 잠깐 깨어난다. 그래서 ``launch()``는
/// `didFinishLaunching` 안에서(화면이 없어도) 불리고, 깨어날 때마다 할 일(tick, outbox)을 한다.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    let configuration = AppConfiguration(infoDictionary: Bundle.main.infoDictionary ?? [:])
    let location = LocationService()
    let notifications = NotificationService()
    private let tokenStore: any TokenStore = KeychainTokenStore()
    private let network = NetworkMonitor()
    private let transport: any HTTPTransport = {
        let configuration = URLSessionConfiguration.default
        // 지역 사건으로 깨어난 몇 초 안에 끝나야 한다. 못 받으면 outbox가 다시 보낸다.
        configuration.timeoutIntervalForRequest = 15
        configuration.waitsForConnectivity = false
        return URLSessionTransport(session: URLSession(configuration: configuration))
    }()

    private(set) var session: RecordingSession?
    @Published private(set) var startupError: String?
    @Published private(set) var locationStatus: CLAuthorizationStatus = .notDetermined
    @Published private(set) var notificationStatus: UNAuthorizationStatus = .notDetermined
    @Published private(set) var hasToken = false
    @Published private(set) var routeError: String?
    @Published private(set) var refreshing = false
    /// 형식이 맞지 않아 옆으로 옮긴 저장 파일 (기록 확인 화면에 알린다).
    @Published private(set) var unreadableFiles: [String] = []
    /// 세션 상태가 바뀔 때마다 오른다 (화면 갱신용).
    @Published private(set) var revision = 0

    private var launched = false
    private var ticker: Task<Void, Never>?

    var client: APIClient? {
        configuration.client(token: try? tokenStore.read(), transport: transport)
    }

    // MARK: 생명주기

    func launch() {
        guard !launched else { return }
        launched = true
        notifications.activate()
        BackgroundRefresh.register { [weak self] in await self?.backgroundRefresh() }
        #if DEBUG
            try? configuration.migrateDebugToken(into: tokenStore)
        #endif
        hasToken = (try? tokenStore.read()) != nil
        locationStatus = location.authorizationStatus

        do {
            let directory = try AppPaths.dataDirectory()
            unreadableFiles = AppPaths.unreadableFiles(in: directory)
            let session = try RecordingSession(directory: directory, location: location, notifier: notifications)
            session.onChange = { [weak self] in self?.revision += 1 }
            session.outbox.background = UIKitBackgroundActivity()
            session.outbox.client = client
            self.session = session
        } catch {
            // 재부팅 후 첫 잠금 해제 전에는 보호된 파일을 읽을 수 없다. 빈 상태로 시작해 덮어쓰지 않는다.
            startupError = "기록 파일을 열지 못했어요. 기기 잠금을 해제한 뒤 앱을 다시 열어 주세요.\n\(error)"
            return
        }
        location.onEvents = { [weak self] events in self?.session?.handle(events) }
        location.onAuthorizationChange = { [weak self] in
            guard let self else { return }
            locationStatus = location.authorizationStatus
        }
        notifications.onEvents = { [weak self] events in self?.session?.handle(events) }
        network.onRestored = { [weak self] in self?.session?.outbox.expedite() }
        network.start()
        // 깨어날 때마다: 시간 초과·GPS 묶음 정리, 밀린 전송.
        session?.handle(.tick(Date()))
    }

    func becameActive() async {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
                self?.session?.handle(.tick(Date()))
            }
        }
        locationStatus = location.authorizationStatus
        notificationStatus = await notifications.authorizationStatus()
        session?.handle(.tick(Date()))
        session?.outbox.expedite()
        let next = await refresh()
        BackgroundRefresh.schedule(at: next)
    }

    func enteredBackground() {
        ticker?.cancel()
        ticker = nil
    }

    private func backgroundRefresh() async -> Date? {
        session?.handle(.tick(Date()))
        let next = await refresh()
        await session?.outbox.flush()
        return next
    }

    /// 경로를 다시 받아 geofence를 맞추고, 출발 알림을 갱신한다. 다음 백그라운드 조회 시각을 돌려준다.
    @discardableResult
    func refresh() async -> Date? {
        guard let session, let client else { return session?.outbox.nextWakeAt }
        refreshing = true
        defer { refreshing = false }
        do {
            let fetched = try await fetchActiveRoutes(client)
            session.updateRoutes(fetched.routes, names: fetched.names)
            routeError = nil
        } catch {
            // 오프라인이면 마지막으로 받은 경로로 계속 기록한다.
            routeError = (error as? APIError)?.message ?? String(describing: error)
        }
        let nextAlertRefresh = await session.refreshDepartureAlerts(client: client, scheduler: notifications)
        return [nextAlertRefresh, session.outbox.nextWakeAt].compactMap { $0 }.min()
    }

    // MARK: 권한

    func requestLocation() {
        location.requestAuthorization()
    }

    func requestNotifications() async {
        _ = await notifications.requestAuthorization()
        notificationStatus = await notifications.authorizationStatus()
    }

    // MARK: 설정

    func saveToken(_ token: String) async {
        do {
            try tokenStore.write(token.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            routeError = "토큰을 저장하지 못했어요: \(error)"
            return
        }
        hasToken = (try? tokenStore.read()) != nil
        session?.outbox.client = client
        session?.outbox.resume()
        await refresh()
    }
}
