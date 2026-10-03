// swift-tools-version:6.0
import PackageDescription

// 기록 코어: geofence 계획, 기록 상태기계, 오프라인 outbox, 출발 알림 계산 (#82, ADR 0003).
// Apple 전용 프레임워크(CoreLocation, UserNotifications, SwiftUI)에 의존하지 않아 Linux CI에서 테스트된다.
// 앱 타깃은 시스템 이벤트를 `RecorderEvent`로 바꿔 넣고, 나온 `RecorderEffect`를 실행하는 어댑터만 갖는다.
let package = Package(
    name: "WhenIOffRecorder",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "WhenIOffRecorder", targets: ["WhenIOffRecorder"])
    ],
    dependencies: [
        .package(path: "../WhenIOffKit")
    ],
    targets: [
        .target(
            name: "WhenIOffRecorder",
            dependencies: [.product(name: "WhenIOffKit", package: "WhenIOffKit")]
        ),
        .testTarget(
            name: "WhenIOffRecorderTests",
            dependencies: ["WhenIOffRecorder"]
        ),
    ]
)
