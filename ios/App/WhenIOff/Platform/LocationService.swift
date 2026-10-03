import CoreLocation
import Foundation
import WhenIOffRecorder

/// CoreLocation 어댑터 (ADR 0003 §3, §6). 지역 감시 사건과 trip 중 위치를 ``RecorderEvent``로 바꾼다.
///
/// `CLMonitor`(ADR 1순위) 대신 `CLLocationManager`의 지역 감시를 쓴다 — 오래 쓰인 API라 백그라운드 재실행 동작이
/// 잘 알려져 있다. 대가로 사건에 발생 시각이 없어 **전달된 시각**을 쓴다(ADR 0003 §3 "결과"에 기록). 바꾸려면 이
/// 파일만 바꾸면 된다.
///
/// 메인 스레드에서 만든다. CoreLocation은 만든 스레드의 런루프로 델리게이트를 부르므로 콜백도 메인이다.
/// 앱이 지역 사건으로 백그라운드에서 다시 실행되면 `application(_:didFinishLaunchingWithOptions:)`에서 이 객체가
/// 만들어지고 델리게이트가 붙어야 사건을 받는다.
@MainActor
final class LocationService: NSObject, LocationControlling {
    private let manager = CLLocationManager()
    /// 지역 진입·이탈, 위치. ``RecordingSession/handle(_:)``로 간다.
    var onEvents: (([RecorderEvent]) -> Void)?
    var onAuthorizationChange: (() -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .otherNavigation
    }

    // MARK: 권한

    var authorizationStatus: CLAuthorizationStatus { manager.authorizationStatus }
    var hasPreciseLocation: Bool { manager.accuracyAuthorization == .fullAccuracy }

    /// iOS는 "항상 허용"을 두 단계로만 준다: 먼저 사용 중 허용, 그다음 항상 허용 요청.
    func requestAuthorization() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse: manager.requestAlwaysAuthorization()
        default: break
        }
    }

    // MARK: LocationControlling

    var monitoredRegions: [MonitoredRegion] {
        manager.monitoredRegions.compactMap { region in
            guard let circle = region as? CLCircularRegion else { return nil }
            return MonitoredRegion(
                identifier: circle.identifier,
                center: Coordinate(lat: circle.center.latitude, lng: circle.center.longitude),
                radius: circle.radius)
        }
    }

    func startMonitoring(_ region: PlannedRegion) {
        guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return }
        let circle = CLCircularRegion(
            center: CLLocationCoordinate2D(latitude: region.center.lat, longitude: region.center.lng),
            radius: min(region.radius, manager.maximumRegionMonitoringDistance),
            identifier: region.identifier)
        circle.notifyOnEntry = true
        circle.notifyOnExit = true
        manager.startMonitoring(for: circle)
    }

    func stopMonitoring(identifier: String) {
        for region in manager.monitoredRegions where region.identifier == identifier {
            manager.stopMonitoring(for: region)
        }
    }

    /// trip 중에만 (배터리). 백그라운드에서도 계속 받도록 `UIBackgroundModes: location`과 함께 켠다.
    func startLocationUpdates() {
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    func stopLocationUpdates() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
    }
}

extension LocationService: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        let event = RecorderEvent.regionEntered(region.identifier, at: Date())
        MainActor.assumeIsolated { onEvents?([event]) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        let event = RecorderEvent.regionExited(region.identifier, at: Date())
        MainActor.assumeIsolated { onEvents?([event]) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // 정확도 필터(100m)와 음수 속도 제거는 Recorder가 한다. 음수 정확도는 "무효"라 그대로 넘긴다.
        let events = locations.map { location in
            RecorderEvent.location(
                LocationSample(
                    recordedAt: location.timestamp,
                    coordinate: Coordinate(lat: location.coordinate.latitude, lng: location.coordinate.longitude),
                    accuracyM: location.horizontalAccuracy,
                    speedMps: location.speed))
        }
        MainActor.assumeIsolated { onEvents?(events) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated { onAuthorizationChange?() }
    }

    nonisolated func locationManager(
        _ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: any Error
    ) {
        NSLog("[wio] region monitoring failed for %@: %@", region?.identifier ?? "-", String(describing: error))
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        NSLog("[wio] location failed: %@", String(describing: error))
    }
}
