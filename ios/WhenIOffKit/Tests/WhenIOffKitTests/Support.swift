import Foundation
import Testing

@testable import WhenIOffKit

/// 로컬 백엔드를 실제로 띄워 동탄→수서(GTX-A) 경로로 기록 흐름을 한 바퀴 돌려 받은 응답 (#34).
enum Fixture {
    static func data(_ name: String) throws -> Data {
        let url = try #require(
            Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"),
            "missing fixture \(name).json"
        )
        return try Data(contentsOf: url)
    }

    static func decode<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
        try WhenIOffJSON.decoder().decode(type, from: data(name))
    }
}

/// 기준 시각. 구현(Calendar)과 독립적으로 Python `datetime`으로 계산한 epoch 초다.
enum Epoch {
    /// 2026-09-27T22:38:40Z
    static let stopArrival: TimeInterval = 1_790_548_720
    /// 2026-09-27T22:31:05Z
    static let leftHome: TimeInterval = 1_790_548_265
    /// 2026-09-27T14:59:59Z = KST 2026-09-27 23:59:59
    static let lastSecondOfKstDay: TimeInterval = 1_790_521_199
}

/// 요청을 기록하고 미리 넣어 둔 응답을 순서대로 돌려준다.
actor RecordingTransport: HTTPTransport {
    enum Reply {
        case response(HTTPResponse)
        case failure(any Error)
    }

    private var replies: [Reply]
    private(set) var requests: [HTTPRequest] = []

    init(_ replies: [Reply]) {
        self.replies = replies
    }

    static func ok(_ status: Int = 200, fixture name: String) throws -> RecordingTransport {
        RecordingTransport([.response(HTTPResponse(status: status, body: try Fixture.data(name)))])
    }

    static func ok(_ status: Int = 200, json: String) -> RecordingTransport {
        RecordingTransport([.response(HTTPResponse(status: status, body: Data(json.utf8)))])
    }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        requests.append(request)
        guard !replies.isEmpty else { throw URLError(.cannotConnectToHost) }
        switch replies.removeFirst() {
        case .response(let response): return response
        case .failure(let error): throw error
        }
    }
}

extension HTTPRequest {
    var bodyText: String? { body.flatMap { String(data: $0, encoding: .utf8) } }
}

func client(_ transport: RecordingTransport, base: String = "http://localhost:8080") -> APIClient {
    APIClient(
        configuration: APIConfiguration(baseURL: URL(string: base)!, apiToken: "test-token"),
        transport: transport
    )
}
