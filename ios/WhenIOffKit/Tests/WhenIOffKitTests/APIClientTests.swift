import Foundation
import Testing

@testable import WhenIOffKit

@Suite("APIClient")
struct APIClientTests {
    // MARK: 요청 모양

    @Test func createTripSendsTokenAndExactBody() async throws {
        let transport = try RecordingTransport.ok(201, fixture: "trip_created")
        let trip = try await client(transport).createTrip(
            CreateCommuteTripRequest(
                routeId: 1,
                tripDate: try #require(LocalDate("2026-09-28")),
                leftHomeAt: Timestamp.parse("2026-09-27T22:31:05.123Z")
            )
        )
        #expect(trip.id == 1)

        let request = try #require(await transport.requests.first)
        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "http://localhost:8080/api/v1/commute-trips")
        #expect(request.headers["X-Api-Token"] == "test-token")
        #expect(request.headers["Accept"] == "application/json")
        #expect(request.headers["Content-Type"] == "application/json")
        // tripDate는 날짜 문자열, leftHomeAt은 밀리초 UTC — 실제 백엔드가 이 본문으로 201을 줬다.
        #expect(request.bodyText == #"{"leftHomeAt":"2026-09-27T22:31:05.123Z","routeId":1,"tripDate":"2026-09-28"}"#)
    }

    /// PATCH는 "보낸 필드만 바꾼다". `nil`을 `null`로 보내면 서버가 그 값을 지울 수 있으므로 키 자체를 뺀다.
    @Test func patchOmitsNilFields() async throws {
        let transport = try RecordingTransport.ok(fixture: "trip_patched")
        _ = try await client(transport).updateTrip(
            id: 1,
            UpdateCommuteTripRequest(arrivedDestinationAt: Timestamp.parse("2026-09-27T23:11:02Z"))
        )
        let request = try #require(await transport.requests.first)
        #expect(request.method == "PATCH")
        #expect(request.url.path == "/api/v1/commute-trips/1")
        #expect(request.bodyText == #"{"arrivedDestinationAt":"2026-09-27T23:11:02.000Z"}"#)
    }

    @Test func attemptEndpoints() async throws {
        let transport = RecordingTransport([
            .response(HTTPResponse(status: 201, body: try Fixture.data("attempt_upserted"))),
            .response(HTTPResponse(status: 200, body: try Fixture.data("attempt_patched"))),
        ])
        let api = client(transport)
        _ = try await api.upsertBoardingAttempt(
            tripId: 1,
            UpsertBoardingAttemptRequest(routeLegId: 2, arrivedAtStopAt: Timestamp.parse("2026-09-27T22:38:40Z"))
        )
        let patched = try await api.updateBoardingAttempt(id: 1, UpdateBoardingAttemptRequest(result: .caught))
        #expect(patched.result == .caught)

        let requests = await transport.requests
        #expect(requests.map(\.method) == ["POST", "PATCH"])
        #expect(
            requests.map(\.url.path) == ["/api/v1/commute-trips/1/boarding-attempts", "/api/v1/boarding-attempts/1"])
        #expect(requests[0].bodyText == #"{"arrivedAtStopAt":"2026-09-27T22:38:40.000Z","routeLegId":2}"#)
        #expect(requests[1].bodyText == #"{"result":"CAUGHT"}"#)
    }

    @Test func getRequestsCarryNoBody() async throws {
        let transport = try RecordingTransport.ok(fixture: "routes_list")
        _ = try await client(transport).routes()
        let request = try #require(await transport.requests.first)
        #expect(request.method == "GET")
        #expect(request.body == nil)
        #expect(request.headers["Content-Type"] == nil)
        #expect(request.headers["X-Api-Token"] == "test-token")
    }

    @Test func tripSearchQuery() async throws {
        let transport = RecordingTransport([
            .response(HTTPResponse(status: 200, body: try Fixture.data("trips_list"))),
            .response(HTTPResponse(status: 200, body: try Fixture.data("trips_list"))),
        ])
        let api = client(transport)
        let day = try #require(LocalDate("2026-09-28"))
        _ = try await api.trips(routeId: 1, from: day, to: day)
        _ = try await api.trips()

        let urls = await transport.requests.map(\.url.absoluteString)
        #expect(urls[0] == "http://localhost:8080/api/v1/commute-trips?routeId=1&from=2026-09-28&to=2026-09-28")
        #expect(urls[1] == "http://localhost:8080/api/v1/commute-trips")
    }

    @Test func recommendationQueryUsesUTCInstant() async throws {
        let transport = RecordingTransport.ok(
            json: #"""
                {"recommendedLeaveHomeAt":"2026-09-27T22:24:00Z","targetArrivalAt":"2026-09-28T00:00:00Z",
                 "catchProbability":0.91,"bufferSeconds":660,"modelVersion":"v1","computedAt":"2026-09-27T21:00:00Z"}
                """#
        )
        let recommendation = try await client(transport).recommendation(
            routeId: 1,
            targetArrivalAt: try #require(Timestamp.parse("2026-09-28T09:00:00+09:00"))
        )
        #expect(recommendation.bufferSeconds == 660)
        let url = try #require(await transport.requests.first?.url.absoluteString)
        #expect(
            url
                == "http://localhost:8080/api/v1/commute-routes/1/recommendation?targetArrivalAt=2026-09-28T00:00:00.000Z"
        )
    }

    @Test(
        "base URL with a path prefix",
        arguments: ["https://example.com/wio", "https://example.com/wio/"]
    )
    func baseURLPrefix(base: String) async throws {
        let transport = try RecordingTransport.ok(fixture: "routes_list")
        _ = try await client(transport, base: base).routes()
        #expect(await transport.requests.first?.url.absoluteString == "https://example.com/wio/api/v1/commute-routes")
    }

    // MARK: 에러

    @Test func problemJSONBecomesHTTPError() async throws {
        let transport = try RecordingTransport.ok(404, fixture: "error_404_recommendation")
        let error = await #expect(throws: APIError.self) {
            _ = try await client(transport).latestRecommendation(routeId: 1)
        }
        #expect(error?.status == 404)
        #expect(error?.message == "no recommendation for commute route 1")
    }

    @Test func unauthorizedIsProblemJSONToo() async throws {
        let transport = try RecordingTransport.ok(401, fixture: "error_401")
        let error = await #expect(throws: APIError.self) { _ = try await client(transport).routes() }
        #expect(error?.status == 401)
        #expect(error?.message == "missing or invalid X-Api-Token header")
    }

    /// 게이트웨이/프록시는 JSON이 아닌 평문을 줄 수 있다. 파싱에 실패해도 원문을 잃지 않는다.
    @Test func nonJSONErrorBodyIsKept() async throws {
        let transport = RecordingTransport.ok(502, json: "Bad Gateway")
        let error = await #expect(throws: APIError.self) { _ = try await client(transport).routes() }
        #expect(error == .http(status: 502, problem: nil, body: "Bad Gateway"))
    }

    @Test func unreadableSuccessBodyIsADecodingError() async throws {
        let transport = RecordingTransport.ok(json: #"{"unexpected":true}"#)
        let error = await #expect(throws: APIError.self) { _ = try await client(transport).routes() }
        guard case .decoding = error else {
            Issue.record("expected .decoding, got \(String(describing: error))")
            return
        }
    }

    /// `POST /commute-trips`는 멱등이 아니다. 응답을 못 받았을 때 클라이언트가 알아서 다시 보내면
    /// 서버에 trip이 두 개 생길 수 있으므로 **한 번만** 보내고 transport 에러를 그대로 올린다.
    @Test func createTripIsNeverRetried() async throws {
        let transport = RecordingTransport([.failure(URLError(.timedOut))])
        let error = await #expect(throws: APIError.self) {
            _ = try await client(transport).createTrip(
                CreateCommuteTripRequest(routeId: 1, tripDate: try #require(LocalDate("2026-09-28")))
            )
        }
        guard case .transport = error else {
            Issue.record("expected .transport, got \(String(describing: error))")
            return
        }
        #expect(await transport.requests.count == 1)
    }

    // MARK: GPS 배치

    @Test(
        "GPS batch size is checked before sending",
        arguments: [0, GpsTraceBatchRequest.maxPoints + 1]
    )
    func gpsBatchOutOfRangeIsNotSent(count: Int) async throws {
        let transport = RecordingTransport([])
        let points = (0..<count).map {
            GpsPoint(recordedAt: Date(timeIntervalSince1970: Double($0)), lat: 37, lng: 127)
        }
        let error = await #expect(throws: APIError.self) {
            _ = try await client(transport).uploadGpsTraces(GpsTraceBatchRequest(points: points))
        }
        guard case .invalidRequest = error else {
            Issue.record("expected .invalidRequest, got \(String(describing: error))")
            return
        }
        #expect(await transport.requests.isEmpty)
    }

    @Test func gpsBatchAtTheLimitIsSent() async throws {
        let transport = RecordingTransport.ok(json: #"{"accepted":500,"ignored":0}"#)
        let points = (0..<GpsTraceBatchRequest.maxPoints).map {
            GpsPoint(recordedAt: Date(timeIntervalSince1970: Double($0)), lat: 37, lng: 127)
        }
        let response = try await client(transport).uploadGpsTraces(GpsTraceBatchRequest(tripId: 1, points: points))
        #expect(response == GpsTraceBatchResponse(accepted: 500, ignored: 0))
        #expect(await transport.requests.first?.url.path == "/api/v1/gps-traces/batch")
    }
}
