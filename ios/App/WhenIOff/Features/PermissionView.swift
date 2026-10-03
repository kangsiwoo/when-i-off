import CoreLocation
import SwiftUI
import UIKit

/// 권한 안내 (#4: "항상 허용" 플로우와 거부 시 안내).
///
/// 자동 기록은 앱이 꺼져 있을 때 지역 사건으로 깨어나야 해서 위치 "항상 허용"과 정확한 위치가 필요하다.
/// iOS는 "항상"을 두 단계로만 묻는다: 사용 중 허용 → (다시 요청) 항상 허용. 두 번째 요청은 한 번만 뜨므로
/// 그 뒤로는 설정 앱으로 보낸다.
struct PermissionView: View {
    @EnvironmentObject private var model: AppModel
    var skip: (() -> Void)?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("집을 나서고 정류장에 도착하는 순간을 앱이 꺼져 있어도 기록하려면 위치를 \"항상\" 허용해야 해요.")
                    Text("위치는 기록 중일 때만 연속으로 쓰고, 그 외에는 등록한 지점(집·정류장·회사) 근처에 들어오고 나갈 때만 깨어나요.")
                        .foregroundStyle(.secondary)
                }
                Section("위치") {
                    LabeledContent("지금", value: locationText)
                    locationAction
                    if model.location.authorizationStatus == .authorizedAlways, !model.location.hasPreciseLocation {
                        Text("\"정확한 위치\"가 꺼져 있으면 정류장 반경(60~120m)을 구분할 수 없어요. 설정에서 켜 주세요.")
                            .foregroundStyle(.orange)
                        openSettingsButton
                    }
                }
                Section("알림") {
                    LabeledContent("지금", value: notificationText)
                    if model.notificationStatus == .notDetermined {
                        Button("알림 허용") { Task { await model.requestNotifications() } }
                    } else if model.notificationStatus == .denied {
                        Text("정류장에서 탔음/놓쳤음을 누르고 출발 알림을 받으려면 알림이 필요해요.")
                            .foregroundStyle(.secondary)
                        openSettingsButton
                    }
                }
                if let skip {
                    Section {
                        Button("나중에 하기", action: skip)
                    } footer: {
                        Text("\"항상 허용\"이 아니면 자동 기록이 되지 않아요. 설정 탭에서 다시 볼 수 있어요.")
                    }
                }
            }
            .navigationTitle("권한")
        }
    }

    @ViewBuilder private var locationAction: some View {
        switch model.locationStatus {
        case .notDetermined:
            Button("위치 허용") { model.requestLocation() }
        case .authorizedWhenInUse:
            Text("다음 창에서 \"항상 허용으로 변경\"을 골라 주세요. 창이 뜨지 않으면 설정 앱에서 \"항상\"으로 바꿔 주세요.")
                .foregroundStyle(.secondary)
            Button("항상 허용 요청") { model.requestLocation() }
            openSettingsButton
        case .denied, .restricted:
            Text("위치가 꺼져 있어 기록할 수 없어요. 설정 앱 → 위치 → \"항상\"을 골라 주세요.")
                .foregroundStyle(.red)
            openSettingsButton
        default:
            EmptyView()
        }
    }

    private var openSettingsButton: some View {
        Button("설정 앱 열기") {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
    }

    private var locationText: String {
        switch model.locationStatus {
        case .authorizedAlways: return "항상 허용"
        case .authorizedWhenInUse: return "사용 중에만"
        case .denied: return "거부됨"
        case .restricted: return "제한됨"
        case .notDetermined: return "아직 묻지 않음"
        @unknown default: return "알 수 없음"
        }
    }

    private var notificationText: String {
        switch model.notificationStatus {
        case .authorized, .provisional, .ephemeral: return "허용"
        case .denied: return "거부됨"
        case .notDetermined: return "아직 묻지 않음"
        @unknown default: return "알 수 없음"
        }
    }
}
