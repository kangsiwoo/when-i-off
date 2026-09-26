// swift-tools-version:6.0
import PackageDescription

// 앱이 백엔드를 부르는 API 레이어. Apple 전용 프레임워크(SwiftUI, CoreLocation, Security)에 의존하지
// 않으므로 Linux에서도 `swift test`로 빌드·테스트된다 (#34). 앱 타깃은 이 패키지를 가져다 쓴다.
let package = Package(
    name: "WhenIOffKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "WhenIOffKit", targets: ["WhenIOffKit"])
    ],
    targets: [
        .target(name: "WhenIOffKit"),
        .testTarget(
            name: "WhenIOffKitTests",
            dependencies: ["WhenIOffKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
