import SwiftUI

/// 서버 주소(빌드 설정, 읽기 전용)와 API 토큰(Keychain).
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var token = ""
    @State private var saved = false

    var body: some View {
        Form {
            Section {
                LabeledContent("주소", value: model.configuration.baseURL?.absoluteString ?? "없음")
            } header: {
                Text("서버")
            } footer: {
                Text("빌드할 때 Config/Secrets.xcconfig의 WIO_BASE_URL로 정해요.")
            }
            Section {
                LabeledContent("저장된 토큰", value: model.hasToken ? "있음" : "없음")
                SecureField("새 토큰", text: $token)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("저장") {
                    let value = token
                    token = ""
                    Task {
                        await model.saveToken(value)
                        saved = true
                    }
                }
                .disabled(token.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: {
                Text("API 토큰")
            } footer: {
                Text(saved ? "저장했어요. 멈춰 있던 전송을 다시 시작해요." : "기기 Keychain에만 저장돼요 (이 기기 전용, 백업되지 않음).")
            }
            Section("권한") {
                NavigationLink("위치·알림 권한") { PermissionView(skip: nil) }
            }
            Section("앱") {
                LabeledContent(
                    "버전",
                    value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "-")
            }
        }
        .navigationTitle("설정")
    }
}
