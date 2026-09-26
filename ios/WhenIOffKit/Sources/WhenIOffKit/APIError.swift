import Foundation

/// 백엔드 에러 본문 (`application/problem+json`, RFC 9457). 401도 같은 형식으로 온다.
public struct ProblemDetails: Codable, Sendable, Equatable {
    public let type: String?
    public let title: String?
    public let status: Int?
    public let detail: String?
    public let instance: String?
}

public enum APIError: Error, Sendable, Equatable {
    /// 서버가 2xx가 아닌 응답을 줬다. 본문이 problem+json이면 `problem`, 아니면 원문이 `body`에 온다
    /// (게이트웨이가 평문 "Bad Gateway"를 줄 수 있다).
    case http(status: Int, problem: ProblemDetails?, body: String?)
    /// 응답을 받지 못했다 (오프라인, 타임아웃 등). **요청이 서버에 도달했는지는 알 수 없다.**
    case transport(String)
    /// 2xx였지만 본문을 모델로 읽지 못했다 — 백엔드 계약이 바뀐 것이다.
    case decoding(String)
    /// 보내기 전에 클라이언트가 거부했다 (예: GPS 배치 500개 초과).
    case invalidRequest(String)

    public var status: Int? {
        if case .http(let status, _, _) = self { return status }
        return nil
    }

    /// 사람이 읽을 한 줄. 서버의 `detail`이 있으면 그것을 쓴다.
    public var message: String {
        switch self {
        case .http(let status, let problem, let body):
            return problem?.detail ?? problem?.title ?? body ?? "HTTP \(status)"
        case .transport(let reason): return reason
        case .decoding(let reason): return reason
        case .invalidRequest(let reason): return reason
        }
    }
}
