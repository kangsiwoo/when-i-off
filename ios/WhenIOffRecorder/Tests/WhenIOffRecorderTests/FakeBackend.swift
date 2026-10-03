import Foundation
import WhenIOffKit

#if canImport(FoundationNetworking)
    import FoundationNetworking
#endif

/// 백엔드의 멱등 규칙만 흉내 내는 가짜 서버 (API.md #37, #38, GPS 배치).
///
/// - trip 생성: `(routeId, leftHomeAt)`이 같으면 기존 trip을 200으로
/// - 탑승 시도: `(trip, routeLegId, attemptSeq)` upsert, 보낸 필드만 덮어씀. 번호 건너뛰기는 400
/// - GPS: `recordedAt` 중복 무시
/// - trip 삭제: 있으면 204(시도도 함께), 없으면 404 (#88)
///
/// `mode`로 네트워크를 흉내 낸다: `.offline`은 서버에 닿지 않고, `.dropResponses`는 서버가 처리한 뒤 응답이 사라진다
/// (지하에서 가장 곤란한 경우 — 앱은 처리됐는지 모른다).
actor FakeBackend: HTTPTransport {
    enum Mode {
        case online
        case offline
        case dropResponses
    }

    struct Trip {
        var id: Int64
        var routeId: Int64
        var tripDate: String
        var leftHomeAt: String
        var arrivedDestinationAt: String?
    }

    var mode: Mode = .online
    private(set) var trips: [Trip] = []
    /// tripId → legId → attemptSeq → 필드
    private(set) var attempts: [Int64: [Int64: [Int: [String: Any]]]] = [:]
    private(set) var gpsTimes: Set<String> = []
    private(set) var requestCount = 0
    private var forcedStatus: [String: Int] = [:]
    /// 지운 trip의 id를 다시 쓰지 않는다 (서버의 BIGSERIAL처럼).
    private var nextTripId: Int64 = 31

    func setMode(_ mode: Mode) { self.mode = mode }

    /// 다음 `method path` 요청 한 번에 이 상태 코드를 돌려준다.
    func fail(_ method: String, _ pathSuffix: String, status: Int) { forcedStatus["\(method) \(pathSuffix)"] = status }

    /// 저장된 시도의 필드 값 (문자열 필드만). 시도가 없으면 `nil`, 필드가 없으면 `""`.
    func attemptField(trip: Int64, leg: Int64, seq: Int, _ field: String) -> String? {
        attempts[trip]?[leg]?[seq].map { $0[field] as? String ?? "" }
    }

    func attemptCount(trip: Int64) -> Int { attempts[trip]?.values.reduce(0) { $0 + $1.count } ?? 0 }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        requestCount += 1
        if mode == .offline { throw URLError(.notConnectedToInternet) }
        let response = handle(request)
        if mode == .dropResponses { throw URLError(.networkConnectionLost) }
        return response
    }

    private func handle(_ request: HTTPRequest) -> HTTPResponse {
        let path = request.url.path.replacingOccurrences(of: "/api/v1", with: "")
        for (key, status) in forcedStatus where "\(request.method) \(path)".hasSuffix(key) {
            forcedStatus[key] = nil
            return problem(status, "forced")
        }
        let body = request.body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
        let parts = path.split(separator: "/").map(String.init)

        switch (request.method, parts.count, parts.first) {
        case ("POST", 1, "commute-trips"):
            let routeId = (body["routeId"] as! NSNumber).int64Value
            let leftHomeAt = body["leftHomeAt"] as! String
            if let existing = trips.first(where: { $0.routeId == routeId && $0.leftHomeAt == leftHomeAt }) {
                return json(200, tripJSON(existing))
            }
            let trip = Trip(
                id: nextTripId, routeId: routeId, tripDate: body["tripDate"] as! String, leftHomeAt: leftHomeAt)
            nextTripId += 1
            trips.append(trip)
            return json(201, tripJSON(trip))
        case ("PATCH", 2, "commute-trips"):
            guard let index = trips.firstIndex(where: { String($0.id) == parts[1] }) else {
                return problem(404, "no trip")
            }
            if let left = body["leftHomeAt"] as? String { trips[index].leftHomeAt = left }
            if let arrived = body["arrivedDestinationAt"] as? String { trips[index].arrivedDestinationAt = arrived }
            return json(200, tripJSON(trips[index]))
        case ("DELETE", 2, "commute-trips"):
            guard let index = trips.firstIndex(where: { String($0.id) == parts[1] }) else {
                return problem(404, "no trip")
            }
            attempts[trips.remove(at: index).id] = nil
            return HTTPResponse(status: 204, body: Data())
        case ("POST", 3, "commute-trips"):
            guard let tripId = Int64(parts[1]), trips.contains(where: { $0.id == tripId }) else {
                return problem(404, "no trip")
            }
            let legId = (body["routeLegId"] as! NSNumber).int64Value
            let seq = (body["attemptSeq"] as? NSNumber)?.intValue ?? 1
            var byLeg = attempts[tripId, default: [:]][legId, default: [:]]
            guard seq <= (byLeg.keys.max() ?? 0) + 1 else { return problem(400, "attemptSeq \(seq) skips a number") }
            let created = byLeg[seq] == nil
            byLeg[seq, default: [:]].merge(body) { _, new in new }
            attempts[tripId, default: [:]][legId] = byLeg
            return json(
                created ? 201 : 200,
                """
                {"id":\(seq),"tripId":\(tripId),"routeLegId":\(legId),"attemptSeq":\(seq),
                "result":"\(byLeg[seq]?["result"] as? String ?? "UNKNOWN")","createdAt":"2026-09-27T22:38:40Z"}
                """)
        case ("POST", 2, "gps-traces"):
            let points = body["points"] as! [[String: Any]]
            var accepted = 0
            for point in points where gpsTimes.insert(point["recordedAt"] as! String).inserted { accepted += 1 }
            return json(200, #"{"accepted":\#(accepted),"ignored":\#(points.count - accepted)}"#)
        default:
            return problem(404, "unknown \(request.method) \(path)")
        }
    }

    private func tripJSON(_ trip: Trip) -> String {
        let arrived = trip.arrivedDestinationAt.map { #","arrivedDestinationAt":"\#($0)""# } ?? ""
        return """
            {"id":\(trip.id),"routeId":\(trip.routeId),"tripDate":"\(trip.tripDate)","leftHomeAt":"\(trip.leftHomeAt)"\(arrived),
            "createdAt":"2026-09-27T22:31:06Z","boardingAttempts":[]}
            """
    }

    private func json(_ status: Int, _ text: String) -> HTTPResponse {
        HTTPResponse(status: status, body: Data(text.utf8))
    }

    private func problem(_ status: Int, _ detail: String) -> HTTPResponse {
        json(status, #"{"title":"error","status":\#(status),"detail":"\#(detail)"}"#)
    }
}
