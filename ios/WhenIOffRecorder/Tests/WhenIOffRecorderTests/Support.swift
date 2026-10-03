import Foundation
import WhenIOffKit

@testable import WhenIOffRecorder

/// KST 2026-09-28(월) `hour:minute:second`. 2026-09-27T15:00:00Z = KST 09-28 00:00 (epoch 1_790_521_200,
/// WhenIOffKit 테스트의 `Epoch.lastSecondOfKstDay` + 1초).
func kst(_ hour: Int, _ minute: Int, _ second: Int = 0) -> Date {
    Date(timeIntervalSince1970: 1_790_521_200 + TimeInterval(hour * 3600 + minute * 60 + second))
}

/// 동탄 집 → (GTX-A) 동탄역 → 수서역 → (버스) → 회사, 그리고 그 반대 퇴근 경로.
/// 수서 GTX역과 수서역 버스정류장은 약 24m 떨어져 한 geofence로 합쳐진다 (환승).
enum Sample {
    static let home = Coordinate(lat: 37.199, lng: 127.099)
    static let office = Coordinate(lat: 37.489, lng: 127.105)
    static let dongtan = StopPoint(id: 4, name: "동탄", coordinate: Coordinate(lat: 37.201167, lng: 127.095111))
    static let suseo = StopPoint(id: 1, name: "수서", coordinate: Coordinate(lat: 37.48694, lng: 127.10194))
    static let suseoBus = StopPoint(id: 11, name: "수서역", coordinate: Coordinate(lat: 37.4870, lng: 127.1022))
    static let officeBus = StopPoint(id: 12, name: "회사앞", coordinate: Coordinate(lat: 37.4880, lng: 127.1040))

    static let gtxLegId: Int64 = 2
    static let busLegId: Int64 = 5
    static let homeGtxLegId: Int64 = 7

    static let toWork = RecorderRoute(
        id: 1,
        direction: .toWork,
        origin: home,
        destination: office,
        transitLegs: [
            RecorderTransitLeg(legId: gtxLegId, mode: .gtx, lineName: "GTX-A (수서~동탄)", board: dongtan, alight: suseo),
            RecorderTransitLeg(legId: busLegId, mode: .bus, lineName: "402", board: suseoBus, alight: officeBus),
        ]
    )

    static let toHome = RecorderRoute(
        id: 2,
        direction: .toHome,
        origin: office,
        destination: home,
        transitLegs: [
            RecorderTransitLeg(
                legId: homeGtxLegId, mode: .gtx, lineName: "GTX-A (수서~동탄)", board: suseo, alight: dongtan)
        ]
    )

    static let routes = [toWork, toHome]

    static func plan(at now: Date = kst(7, 0)) -> GeofencePlan {
        GeofencePlanner().plan(routes: routes, now: now)
    }

    static func recorder(at now: Date = kst(7, 0)) -> Recorder {
        Recorder(routes: routes, plan: plan(at: now))
    }

    static func region(_ role: RegionRole, in plan: GeofencePlan = Sample.plan()) -> String {
        plan.regions.first { $0.roles.contains(role) }!.identifier
    }

    // 자주 쓰는 지역 식별자 (출근 시각 기준 계획)
    static var homeRegion: String { region(.origin(routeId: 1)) }
    static var officeRegion: String { region(.destination(routeId: 1)) }
    static var dongtanRegion: String { region(.boardStop(routeId: 1, legId: gtxLegId)) }
    static var suseoRegion: String { region(.alightStop(routeId: 1, legId: gtxLegId)) }
    static var officeBusRegion: String { region(.alightStop(routeId: 1, legId: busLegId)) }
}

extension Array where Element == RecorderEffect {
    var commands: [OutboxCommand] {
        compactMap { effect in
            if case .enqueue(let command) = effect { return command }
            return nil
        }
    }

    var upserts: [UpsertBoardingAttemptRequest] {
        commands.compactMap { command in
            if case .upsertAttempt(_, let request) = command { return request }
            return nil
        }
    }

    var reviews: [ReviewNote.Kind] {
        compactMap { effect in
            if case .needsReview(let note) = effect { return note.kind }
            return nil
        }
    }

    var prompts: [BoardingPrompt] {
        compactMap { effect in
            if case .promptBoarding(let prompt) = effect { return prompt }
            return nil
        }
    }
}

extension Recorder {
    /// 여러 사건을 차례로 넣고 나온 효과를 모두 모은다.
    mutating func run(_ events: [RecorderEvent]) -> [RecorderEffect] {
        events.flatMap { handle($0) }
    }
}
