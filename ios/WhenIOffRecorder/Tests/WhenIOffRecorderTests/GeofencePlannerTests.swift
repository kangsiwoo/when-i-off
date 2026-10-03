import Foundation
import Testing
import WhenIOffKit

@testable import WhenIOffRecorder

@Suite("GeofencePlanner")
struct GeofencePlannerTests {
    @Test func mergesSharedPlacesAndUsesRadiiByStopMode() {
        let plan = Sample.plan(at: kst(7, 0))
        // 집 · 회사 · 동탄역 · 수서(GTX역 + 버스정류장, 24m) · 회사앞 정류장
        #expect(plan.regions.count == 5)
        #expect(plan.dropped.isEmpty)

        let home = plan.regions.first { $0.roles.contains(.origin(routeId: 1)) }!
        #expect(home.roles == [.origin(routeId: 1), .destination(routeId: 2)])
        #expect(home.radius == 100)
        #expect(home.identifier == "wio.o.1")

        let suseo = plan.regions.first { $0.roles.contains(.alightStop(routeId: 1, legId: Sample.gtxLegId)) }!
        #expect(
            suseo.roles == [
                .alightStop(routeId: 1, legId: Sample.gtxLegId),
                .boardStop(routeId: 1, legId: Sample.busLegId),
                .boardStop(routeId: 2, legId: Sample.homeGtxLegId),
            ])
        // 지하철·GTX 120m와 버스 60m가 합쳐지면 큰 쪽
        #expect(suseo.radius == 120)

        let officeBus = plan.regions.first { $0.roles.contains(.alightStop(routeId: 1, legId: Sample.busLegId)) }!
        #expect(officeBus.radius == 60)
    }

    @Test func identifiersAreStableAcrossReplans() {
        let morning = Sample.plan(at: kst(7, 0)).regions.map(\.identifier).sorted()
        let again = Sample.plan(at: kst(7, 30)).regions.map(\.identifier).sorted()
        #expect(morning == again)
    }

    @Test func adaptsTheServerRouteDetail() throws {
        let json = """
            {"route":{"id":1,"name":"출근","direction":"TO_WORK","originLat":37.199,"originLng":127.099,
            "destinationLat":37.489,"destinationLng":127.105,"isActive":true,"createdAt":"2026-09-26T16:26:05.215471Z"},
            "legs":[{"id":1,"seqOrder":1,"legType":"WALK","signalCrossings":[]},
            {"id":2,"seqOrder":2,"legType":"TRANSIT","transitLineId":1,"boardStopId":4,"alightStopId":1,
            "transitLine":{"id":1,"mode":"GTX","name":"GTX-A (수서~동탄)","hasRealtimeApi":false,
            "createdAt":"2026-09-26T16:24:56.569985Z"},
            "boardStop":{"id":4,"mode":"GTX","name":"동탄","lat":37.201167,"lng":127.095111,
            "createdAt":"2026-09-26T16:24:56.569985Z"},
            "alightStop":{"id":1,"mode":"GTX","name":"수서","lat":37.48694,"lng":127.10194,
            "createdAt":"2026-09-26T16:24:56.569985Z"},"signalCrossings":[]}]}
            """
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            return Timestamp.parse(text)!
        }
        let detail = try decoder.decode(CommuteRouteDetail.self, from: Data(json.utf8))
        let route = RecorderRoute(detail)
        #expect(route.direction == .toWork)
        #expect(route.transitLegs.count == 1)
        #expect(route.transitLegs[0].legId == 2)
        #expect(route.transitLegs[0].mode == .gtx)
        #expect(route.transitLegs[0].board.name == "동탄")
        #expect(route.transitLegs[0].alight.coordinate == Coordinate(lat: 37.48694, lng: 127.10194))
    }

    /// 정류장이 많은 경로: 출발지가 먼저, 그 다음 도착지, 그 다음 구간 순서로 승차·하차. 20개에서 자른다.
    @Test func capsAtTwentyWithDeterministicPriority() {
        let legs: [RecorderTransitLeg] = (0..<12).map { (i: Int) -> RecorderTransitLeg in
            RecorderTransitLeg(
                legId: Int64(100 + i),
                mode: .bus,
                lineName: "\(i)",
                board: StopPoint(
                    id: Int64(i * 2), name: "b\(i)", coordinate: Coordinate(lat: 37.0 + Double(i) * 0.01, lng: 127.0)),
                alight: StopPoint(
                    id: Int64(i * 2 + 1), name: "a\(i)",
                    coordinate: Coordinate(lat: 37.005 + Double(i) * 0.01, lng: 127.0))
            )
        }
        let long = RecorderRoute(
            id: 9, direction: .toWork, origin: Coordinate(lat: 36.9, lng: 127.0),
            destination: Coordinate(lat: 38.0, lng: 127.0),
            transitLegs: legs)
        let plan = GeofencePlanner().plan(routes: [long], now: kst(7, 0))

        #expect(plan.regions.count == 20)
        let first: [RegionRole] = [.origin(routeId: 9)]
        let second: [RegionRole] = [.destination(routeId: 9)]
        let last: [RegionRole] = [.alightStop(routeId: 9, legId: 108)]
        let dropped: [RegionRole] = (109...111).flatMap { (leg: Int64) -> [RegionRole] in
            [.boardStop(routeId: 9, legId: leg), .alightStop(routeId: 9, legId: leg)]
        }
        #expect(plan.regions[0].roles == first)
        #expect(plan.regions[1].roles == second)
        // 남은 18자리 = 9개 구간의 승차·하차. 10번째 구간부터 버려진다
        #expect(plan.regions[19].roles == last)
        #expect(plan.dropped == dropped)
        // 같은 입력이면 같은 계획
        #expect(GeofencePlanner().plan(routes: [long], now: kst(7, 0)) == plan)
    }

    /// 한도에 걸리면 지금 시각의 평소 방향이 아닌 경로의 정류장이 먼저 빠진다. 그래도 출발지는 남는다.
    @Test func capDropsTheOtherDirectionsStopsFirstButKeepsItsOrigin() {
        let planner = GeofencePlanner(maxRegions: 4)
        let morning = planner.plan(routes: Sample.routes, now: kst(7, 0))
        #expect(morning.regions.map(\.identifier) == ["wio.o.1", "wio.o.2", "wio.b.1.2", "wio.a.1.2"])
        #expect(morning.dropped.contains(.alightStop(routeId: 1, legId: Sample.busLegId)))

        let evening = planner.plan(routes: Sample.routes, now: kst(18, 0))
        #expect(evening.regions[0].identifier == "wio.o.2")
        #expect(evening.regions[1].identifier == "wio.o.1")
        #expect(evening.regions[2].roles.contains(.boardStop(routeId: 2, legId: Sample.homeGtxLegId)))
    }

    /// 진행 중인 trip의 경로는 시각과 무관하게 먼저 걸린다 (출근이 14시를 넘겨도 정류장이 빠지지 않게).
    @Test func activeTripRouteStaysPrimary() {
        let planner = GeofencePlanner(maxRegions: 4)
        let plan = planner.plan(routes: Sample.routes, now: kst(15, 0), activeRouteId: 1)
        #expect(plan.regions[0].identifier == "wio.o.1")
        #expect(plan.regions[2].identifier == "wio.b.1.2")
    }

    @Test func neverExceedsTheSystemLimit() {
        #expect(GeofencePlanner(maxRegions: 50).maxRegions == 20)
    }
}

@Suite("DirectionPolicy")
struct DirectionPolicyTests {
    @Test func usualDirectionFollowsKoreanClock() {
        let policy = DirectionPolicy()
        #expect(policy.usualDirection(at: kst(3, 59)) == .toHome)
        #expect(policy.usualDirection(at: kst(4, 0)) == .toWork)
        #expect(policy.usualDirection(at: kst(13, 59)) == .toWork)
        #expect(policy.usualDirection(at: kst(14, 0)) == .toHome)
    }

    /// 같은 장소가 두 경로의 출발지면 시각으로 고른다.
    @Test func sharedOriginIsResolvedByTimeOfDay() {
        let gym = RecorderRoute(
            id: 3, direction: .toHome, origin: Sample.home, destination: Sample.office, transitLegs: [])
        let routes = [Sample.toWork, gym]
        let roles: [RegionRole] = [.origin(routeId: 1), .origin(routeId: 3)]
        let policy = DirectionPolicy()

        let morning = policy.routeToStart(exiting: roles, routes: routes, at: kst(7, 0))
        #expect(morning?.route.id == 1)
        #expect(morning?.outsideUsualHours == false)

        let evening = policy.routeToStart(exiting: roles, routes: routes, at: kst(19, 0))
        #expect(evening?.route.id == 3)
        #expect(evening?.outsideUsualHours == false)
    }

    @Test func notAnOriginStartsNothing() {
        let policy = DirectionPolicy()
        #expect(
            policy.routeToStart(exiting: [.destination(routeId: 1)], routes: Sample.routes, at: kst(7, 0)) == nil)
    }
}
