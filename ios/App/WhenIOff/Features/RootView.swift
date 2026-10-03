import CoreLocation
import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("wio.permissionsSkipped") private var permissionsSkipped = false

    var body: some View {
        if let error = model.startupError {
            ContentUnavailableView("기록을 열 수 없어요", systemImage: "lock", description: Text(error))
        } else if model.locationStatus != .authorizedAlways && !permissionsSkipped {
            PermissionView(skip: { permissionsSkipped = true })
        } else {
            TabView {
                NavigationStack { StatusView() }
                    .tabItem { Label("기록", systemImage: "figure.walk") }
                NavigationStack { TripListView() }
                    .tabItem { Label("이력", systemImage: "list.bullet") }
                NavigationStack { SettingsView() }
                    .tabItem { Label("설정", systemImage: "gearshape") }
            }
        }
    }
}
