import Foundation
import WhenIOffKit
import WhenIOffRecorder

/// 빌드 때 xcconfig에서 Info.plist로 들어온 값 (ADR 0003 §9).
///
/// - `WIOBaseURL` ← `WIO_BASE_URL` (`Config/Secrets.xcconfig`, 커밋 금지)
/// - `WIODebugAPIToken` ← Debug 빌드에서만 `WIO_API_TOKEN`. Release 번들에는 빈 값이다
struct AppConfiguration: Equatable {
    var baseURL: URL?
    var debugAPIToken: String?

    init(baseURL: URL?, debugAPIToken: String?) {
        self.baseURL = baseURL
        self.debugAPIToken = debugAPIToken
    }

    init(infoDictionary: [String: Any]) {
        let base = (infoDictionary["WIOBaseURL"] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        let url = URL(string: base)
        baseURL = url?.scheme == nil || url?.host == nil ? nil : url
        let token = (infoDictionary["WIODebugAPIToken"] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        debugAPIToken = token.isEmpty ? nil : token
    }

    /// Debug 빌드에 한해 xcconfig 토큰을 첫 실행에 Keychain으로 옮긴다. Keychain에 이미 있으면 건드리지 않는다.
    func migrateDebugToken(into store: any TokenStore) throws {
        guard let debugAPIToken, try store.read() == nil else { return }
        try store.write(debugAPIToken)
    }

    /// 주소와 토큰이 다 있어야 보낸다. 없으면 outbox에 쌓기만 한다.
    func client(token: String?, transport: any HTTPTransport) -> APIClient? {
        guard let baseURL, let token, !token.isEmpty else { return nil }
        return APIClient(configuration: APIConfiguration(baseURL: baseURL, apiToken: token), transport: transport)
    }
}

/// 서버의 활성 경로를 기록 코어 형식으로 받는다.
func fetchActiveRoutes(_ client: APIClient) async throws -> (routes: [RecorderRoute], names: [Int64: String]) {
    let active = try await client.routes().filter(\.isActive)
    var routes: [RecorderRoute] = []
    for route in active {
        routes.append(RecorderRoute(try await client.route(id: route.id)))
    }
    return (routes, Dictionary(active.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first }))
}
