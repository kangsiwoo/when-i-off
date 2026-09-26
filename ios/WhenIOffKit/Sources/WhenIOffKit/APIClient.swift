import Foundation

/// 서버 주소와 토큰. 값은 앱이 xcconfig/Keychain에서 읽어 넣는다 — 이 패키지는 보관하지 않는다.
public struct APIConfiguration: Sendable, Equatable {
    /// 예: `http://192.168.0.10:8080`. 경로 접두사가 있어도 된다 (`https://example.com/wio`).
    public var baseURL: URL
    public var apiToken: String

    public init(baseURL: URL, apiToken: String) {
        self.baseURL = baseURL
        self.apiToken = apiToken
    }
}

/// when-i-off 백엔드 `/api/v1` 클라이언트.
///
/// **자동 재시도를 하지 않는다.** `POST /commute-trips`는 멱등이 아니어서(`(경로, 날짜)` 유일 제약이
/// 없다) 응답을 못 받은 채 다시 보내면 trip이 중복 생성된다. ``APIError/transport(_:)``는
/// "서버에 도달했는지 모른다"는 뜻이므로, 재전송하려는 쪽이 먼저 ``trips(routeId:from:to:)``로
/// 같은 `leftHomeAt`의 trip이 이미 있는지 확인해야 한다 (#34 후속: 오프라인 큐).
///
/// 반대로 탑승 시도 upsert와 GPS 배치는 서버가 중복을 흡수하므로 그대로 다시 보내도 안전하다.
public struct APIClient: Sendable {
    public let configuration: APIConfiguration
    private let transport: any HTTPTransport

    public init(configuration: APIConfiguration, transport: any HTTPTransport = URLSessionTransport()) {
        self.configuration = configuration
        self.transport = transport
    }

    // MARK: 경로

    public func routes() async throws(APIError) -> [CommuteRoute] {
        try await send("GET", ["commute-routes"])
    }

    public func route(id: Int64) async throws(APIError) -> CommuteRouteDetail {
        try await send("GET", ["commute-routes", String(id)])
    }

    // MARK: 이동 기록

    /// 멱등이 아니다 — 타입 설명의 재시도 주의 참고.
    public func createTrip(_ request: CreateCommuteTripRequest) async throws(APIError) -> CommuteTrip {
        try await send("POST", ["commute-trips"], body: request)
    }

    public func updateTrip(id: Int64, _ request: UpdateCommuteTripRequest) async throws(APIError) -> CommuteTrip {
        try await send("PATCH", ["commute-trips", String(id)], body: request)
    }

    /// 모든 조건은 선택이다. 날짜는 `tripDate`(KST 달력) 기준이고 서버는 `to < from`이면 400을 준다.
    public func trips(routeId: Int64? = nil, from: LocalDate? = nil, to: LocalDate? = nil)
        async throws(APIError) -> [CommuteTrip]
    {
        var query: [URLQueryItem] = []
        if let routeId { query.append(URLQueryItem(name: "routeId", value: String(routeId))) }
        if let from { query.append(URLQueryItem(name: "from", value: from.description)) }
        if let to { query.append(URLQueryItem(name: "to", value: to.description)) }
        return try await send("GET", ["commute-trips"], query: query)
    }

    public func upsertBoardingAttempt(tripId: Int64, _ request: UpsertBoardingAttemptRequest)
        async throws(APIError) -> BoardingAttempt
    {
        try await send("POST", ["commute-trips", String(tripId), "boarding-attempts"], body: request)
    }

    public func updateBoardingAttempt(id: Int64, _ request: UpdateBoardingAttemptRequest)
        async throws(APIError) -> BoardingAttempt
    {
        try await send("PATCH", ["boarding-attempts", String(id)], body: request)
    }

    /// 포인트는 1~500개. 범위 밖이면 보내지 않고 ``APIError/invalidRequest(_:)``.
    public func uploadGpsTraces(_ request: GpsTraceBatchRequest) async throws(APIError) -> GpsTraceBatchResponse {
        guard !request.points.isEmpty else {
            throw .invalidRequest("GPS batch must contain at least one point")
        }
        guard request.points.count <= GpsTraceBatchRequest.maxPoints else {
            throw .invalidRequest(
                "GPS batch has \(request.points.count) points; the server accepts at most \(GpsTraceBatchRequest.maxPoints)"
            )
        }
        return try await send("POST", ["gps-traces", "batch"], body: request)
    }

    // MARK: 추천

    /// 추천이 아직 없으면 404다 (빈 200이 아니다). 경로가 없거나 남의 것이어도 같은 404.
    public func recommendation(routeId: Int64, targetArrivalAt: Date) async throws(APIError) -> DepartureRecommendation
    {
        try await send(
            "GET",
            ["commute-routes", String(routeId), "recommendation"],
            query: [URLQueryItem(name: "targetArrivalAt", value: Timestamp.format(targetArrivalAt))]
        )
    }

    /// 목표 시각과 무관하게 가장 최근 계산된 추천. 없으면 404.
    public func latestRecommendation(routeId: Int64) async throws(APIError) -> DepartureRecommendation {
        try await send("GET", ["commute-routes", String(routeId), "recommendation", "latest"])
    }

    // MARK: 전송

    private func send<Response: Decodable>(
        _ method: String,
        _ path: [String],
        query: [URLQueryItem] = []
    ) async throws(APIError) -> Response {
        try await perform(method, path, query: query, body: Data?.none)
    }

    private func send<Body: Encodable, Response: Decodable>(
        _ method: String,
        _ path: [String],
        body: Body
    ) async throws(APIError) -> Response {
        let data: Data
        do {
            data = try WhenIOffJSON.encoder().encode(body)
        } catch {
            throw .invalidRequest("could not encode request body: \(error)")
        }
        return try await perform(method, path, query: [], body: data)
    }

    private func perform<Response: Decodable>(
        _ method: String,
        _ path: [String],
        query: [URLQueryItem],
        body: Data?
    ) async throws(APIError) -> Response {
        let url = try makeURL(path, query: query)
        var headers = [
            "X-Api-Token": configuration.apiToken,
            "Accept": "application/json",
        ]
        if body != nil { headers["Content-Type"] = "application/json" }

        let response: HTTPResponse
        do {
            response = try await transport.send(HTTPRequest(method: method, url: url, headers: headers, body: body))
        } catch {
            throw .transport(String(describing: error))
        }

        guard (200..<300).contains(response.status) else {
            let problem = try? WhenIOffJSON.decoder().decode(ProblemDetails.self, from: response.body)
            // problem+json이 아니면(게이트웨이의 평문 등) 원문을 남긴다.
            let text = problem == nil ? String(data: response.body, encoding: .utf8) : nil
            throw .http(status: response.status, problem: problem, body: text)
        }

        do {
            return try WhenIOffJSON.decoder().decode(Response.self, from: response.body)
        } catch {
            throw .decoding("\(method) \(url.path): \(error)")
        }
    }

    private func makeURL(_ path: [String], query: [URLQueryItem]) throws(APIError) -> URL {
        var url = configuration.baseURL.appendingPathComponent("api").appendingPathComponent("v1")
        for segment in path { url = url.appendingPathComponent(segment) }
        guard !query.isEmpty else { return url }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw .invalidRequest("invalid base URL: \(configuration.baseURL)")
        }
        components.queryItems = query
        // URLComponents는 쿼리의 `+`를 인코딩하지 않는데, 서버는 `+`를 공백으로 읽는다. 시각 오프셋(`+09:00`)이
        // 섞일 일은 없지만(항상 `Z`로 보낸다) 안전하게 막는다.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let result = components.url else {
            throw .invalidRequest("could not build URL for \(path.joined(separator: "/"))")
        }
        return result
    }
}
