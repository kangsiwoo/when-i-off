import SwiftUI
import UIKit

@main
struct WhenIOffApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: Task { await model.becameActive() }
            case .background: model.enteredBackground()
            default: break
            }
        }
    }
}

/// 지역 사건·알림 액션으로 앱이 **화면 없이** 다시 실행될 때도 여기는 불린다. 위치 관리자와 알림 델리게이트,
/// BGTask 등록은 이 안에서 끝나야 사건을 받는다.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        AppModel.shared.launch()
        return true
    }
}
