import Foundation
import Testing

@testable import WhenIOffKit

/// 모든 fixture는 로컬 백엔드의 **실제 응답**이다. 여기서 깨지면 백엔드 계약이 바뀐 것이다.
@Suite("실제 백엔드 응답 디코딩")
struct FixtureDecodingTests {
    @Test func route() throws {
        let route = try Fixture.decode(CommuteRoute.self, "route_created")
        #expect(route.name == "출근")
        #expect(route.direction == .toWork)
        #expect(route.isActive)
        #expect(route.destinationLng == 127.105)
    }

    @Test func routeList() throws {
        #expect(try Fixture.decode([CommuteRoute].self, "routes_list").map(\.id) == [1])
    }

    /// null 필드는 백엔드가 **키째로** 빼고 보낸다 (`non_null`). WALK 구간엔 노선 키가, TRANSIT 구간엔
    /// 좌표 키가 아예 없다.
    @Test func routeDetailWithOmittedNullFields() throws {
        let detail = try Fixture.decode(CommuteRouteDetail.self, "route_detail")
        #expect(detail.legs.map(\.legType) == [.walk, .transit, .walk])

        let walk = detail.legs[0]
        #expect(walk.transitLineId == nil)
        #expect(walk.plannedDistanceM == 450)
        #expect(walk.endLat == 37.201167)  // 동탄역

        let transit = try #require(detail.transitLegs.first)
        #expect(detail.transitLegs.map(\.id) == [2])
        #expect(transit.startLat == nil)
        #expect(transit.boardStopId == 4)  // 동탄 X111
        #expect(transit.alightStopId == 1)  // 수서 X108
        #expect(transit.plannedTravelSec == 1260)
    }

    /// `createdAt`이 나노초(9자리)로 온다 — 메모리의 `Instant`가 DB를 거치지 않고 그대로 나간 값이다.
    @Test func createdTripWithNanosecondTimestampAndPlainDate() throws {
        let trip = try Fixture.decode(CommuteTrip.self, "trip_created")
        #expect(trip.tripDate == LocalDate(year: 2026, month: 9, day: 28))
        #expect(trip.leftHomeAt.map(Timestamp.format) == "2026-09-27T22:31:05.123Z")
        #expect(trip.arrivedDestinationAt == nil)
        #expect(trip.boardingAttempts.isEmpty)
        #expect(Timestamp.format(trip.createdAt) == "2026-09-26T16:06:29.662Z")
    }

    @Test func upsertedAttemptDefaultsToUnknown() throws {
        let attempt = try Fixture.decode(BoardingAttempt.self, "attempt_upserted")
        #expect(attempt.result == .unknown)
        #expect(attempt.arrivedAtStopAt == Date(timeIntervalSince1970: Epoch.stopArrival))
        #expect(attempt.vehicleScheduledOrPredictedAt == nil)
        #expect(attempt.notes == nil)
    }

    @Test func patchedAttempt() throws {
        let attempt = try Fixture.decode(BoardingAttempt.self, "attempt_patched")
        #expect(attempt.result == .caught)
        #expect(attempt.alightedAt.map(Timestamp.format) == "2026-09-27T23:04:30.500Z")
    }

    /// 같은 행의 `createdAt`이 upsert 직후엔 9자리(`.758066920Z`), DB에서 다시 읽으면 6자리(`.758067Z`)로
    /// 온다. 둘 다 읽혀야 하고, 차이는 80ns뿐이다.
    @Test func sameCreatedAtArrivesWithDifferentPrecision() throws {
        let fresh = try Fixture.decode(BoardingAttempt.self, "attempt_upserted")
        let reread = try #require(try Fixture.decode(CommuteTrip.self, "trip_patched").boardingAttempts.first)
        #expect(abs(fresh.createdAt.timeIntervalSince(reread.createdAt)) < 1e-6)
    }

    /// 디코더가 ``Timestamp``의 엄격한 파서를 쓰는지 고정한다. Foundation `.iso8601`(swift-foundation)은
    /// 이 값을 에러 없이 3월 2일로 읽는다 — 그걸로 바꾸면 이 테스트가 깨진다.
    @Test func decoderRejectsANonexistentDateInsteadOfRollingItOver() throws {
        var json = String(decoding: try Fixture.data("attempt_upserted"), as: UTF8.self)
        json = json.replacingOccurrences(of: "2026-09-27T22:38:40Z", with: "2026-02-30T22:38:40Z")
        #expect(throws: DecodingError.self) {
            try WhenIOffJSON.decoder().decode(BoardingAttempt.self, from: Data(json.utf8))
        }
    }

    @Test func tripHistoryIncludesAttempts() throws {
        let trips = try Fixture.decode([CommuteTrip].self, "trips_list")
        #expect(trips.count == 1)
        #expect(trips[0].arrivedDestinationAt.map(Timestamp.format) == "2026-09-27T23:11:02.000Z")
        #expect(trips[0].boardingAttempts.map(\.result) == [.caught])
    }

    @Test(
        "problem+json error bodies",
        arguments: [
            ("error_400_legs", 400, "legs must alternate WALK/TRANSIT (seqOrder 1 and 2)"),
            ("error_401", 401, "missing or invalid X-Api-Token header"),
            ("error_404_recommendation", 404, "no recommendation for commute route 1"),
        ]
    )
    func problemDetails(name: String, status: Int, detail: String) throws {
        let problem = try Fixture.decode(ProblemDetails.self, name)
        #expect(problem.status == status)
        #expect(problem.detail == detail)
    }
}
