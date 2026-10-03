import Foundation
import Testing
import WhenIOffKit

@testable import WhenIOffRecorder

@Suite("DepartureAlertPlanner")
struct DepartureAlertPlannerTests {
    let planner = DepartureAlertPlanner()
    let id = "wio.departure.1.2026-09-28"

    func advice(leave: Date = kst(8, 0), target: Date = kst(9, 0), probability: Double = 0.91) -> DepartureAdvice {
        DepartureAdvice(
            routeId: 1, recommendedLeaveHomeAt: leave, targetArrivalAt: target, catchProbability: probability,
            modelVersion: "v2")
    }

    func plan(
        _ advice: DepartureAdvice?,
        pending: LocalAlert? = nil,
        delivered: Set<String> = [],
        alreadyLeft: Bool = false,
        coldStart: Bool = false,
        now: Date
    ) -> DepartureAlertPlan {
        planner.plan(
            advice: advice, pending: pending, delivered: delivered, alreadyLeft: alreadyLeft, isColdStart: coldStart,
            now: now)
    }

    @Test func schedulesFiveMinutesBeforeTheRecommendedDeparture() throws {
        let result = plan(advice(), now: kst(6, 0))
        let alert = try #require(result.schedule)
        #expect(result.cancel.isEmpty)
        #expect(alert.identifier == id)
        #expect(alert.fireAt == kst(7, 55))
        #expect(alert.title == "지금 나가세요 (성공 확률 91%)")
        #expect(alert.body == "08:00 출발 권장 · 09:00 도착 목표")
    }

    @Test func coldStartSaysTheRecommendationIsConservative() throws {
        let alert = try #require(plan(advice(), coldStart: true, now: kst(6, 0)).schedule)
        #expect(alert.body == "08:00 출발 권장 · 09:00 도착 목표\n아직 데이터가 적어 보수적으로 추천했어요")
    }

    /// 추천이 갱신되면 같은 식별자로 다시 예약한다 (UserNotifications가 교체). 작은 흔들림은 무시.
    @Test func reschedulesOnlyWhenTheRecommendationMoves() throws {
        let first = try #require(plan(advice(), now: kst(6, 0)).schedule)

        #expect(plan(advice(), pending: first, now: kst(6, 15)).isEmpty)
        #expect(plan(advice(leave: kst(8, 0, 20)), pending: first, now: kst(6, 15)).isEmpty)

        let moved = plan(advice(leave: kst(7, 52)), pending: first, now: kst(6, 30))
        #expect(moved.cancel.isEmpty)
        #expect(moved.schedule?.identifier == id)
        #expect(moved.schedule?.fireAt == kst(7, 47))

        let probability = plan(advice(probability: 0.85), pending: first, now: kst(6, 30))
        #expect(probability.schedule?.title == "지금 나가세요 (성공 확률 85%)")
    }

    @Test func insideTheLeadWindowAlertsNowAndAfterDepartureNotAtAll() {
        #expect(plan(advice(), now: kst(7, 57)).schedule?.fireAt == kst(7, 57))
        #expect(plan(advice(), now: kst(8, 0)).isEmpty)

        let pending = LocalAlert(identifier: id, fireAt: kst(7, 55), title: "t", body: "b")
        #expect(plan(advice(), pending: pending, now: kst(8, 1)) == DepartureAlertPlan(cancel: [id]))
    }

    @Test func noRecommendationCancels() {
        let pending = LocalAlert(identifier: id, fireAt: kst(7, 55), title: "t", body: "b")
        #expect(plan(nil, pending: pending, now: kst(6, 0)) == DepartureAlertPlan(cancel: [id]))
        #expect(plan(nil, now: kst(6, 0)).isEmpty)
    }

    @Test func alreadyLeftOrAlreadyAlertedDoesNotAlertAgain() {
        let pending = LocalAlert(identifier: id, fireAt: kst(7, 55), title: "t", body: "b")
        #expect(
            plan(advice(), pending: pending, alreadyLeft: true, now: kst(7, 40)) == DepartureAlertPlan(cancel: [id]))
        #expect(plan(advice(), delivered: [id], now: kst(7, 56)).isEmpty)
    }

    /// 다음날 추천이 오면 전날 알림(남아 있다면)을 지우고 새 날짜로 예약한다.
    @Test func nextDaysRecommendationReplacesTheOldAlert() {
        let old = LocalAlert(identifier: id, fireAt: kst(7, 55), title: "t", body: "b")
        let tomorrow = plan(advice(leave: kst(32, 0), target: kst(33, 0)), pending: old, now: kst(19, 0))
        #expect(tomorrow.cancel == [id])
        #expect(tomorrow.schedule?.identifier == "wio.departure.1.2026-09-29")
    }

    @Test func probabilityIsRoundedDown() {
        #expect(DepartureAlertPlanner.percent(0.999) == 99)
        #expect(DepartureAlertPlanner.percent(1) == 100)
        #expect(DepartureAlertPlanner.percent(-0.1) == 0)
    }

    /// 목표 120분 전부터 15분마다, 출발 시각이 지나면 다음 앱 실행까지 조회하지 않는다.
    @Test func refreshSchedule() {
        #expect(planner.nextRefreshAt(advice: advice(), now: kst(5, 0)) == kst(7, 0))
        #expect(planner.nextRefreshAt(advice: advice(), now: kst(7, 10)) == kst(7, 25))
        #expect(planner.nextRefreshAt(advice: advice(), now: kst(7, 50)) == kst(8, 0))
        #expect(planner.nextRefreshAt(advice: advice(), now: kst(8, 0)) == nil)
        #expect(planner.nextRefreshAt(advice: nil, now: kst(5, 0)) == nil)
    }
}

@Suite("ColdStart")
struct ColdStartTests {
    @Test func v1IsAlwaysColdStart() {
        #expect(ColdStart.isColdStart(modelVersion: "v1", transitLegIds: [2], samplesByLeg: [2: 50]))
    }

    @Test func anyLegBelowFiveSamplesIsColdStart() {
        #expect(ColdStart.isColdStart(modelVersion: "v2", transitLegIds: [2, 5], samplesByLeg: [2: 9, 5: 4]))
        #expect(!ColdStart.isColdStart(modelVersion: "v2", transitLegIds: [2, 5], samplesByLeg: [2: 9, 5: 5]))
        #expect(ColdStart.isColdStart(modelVersion: "v2", transitLegIds: [2], samplesByLeg: [:]))
    }

    @Test func countsAttemptsWithAnObservedDeparture() throws {
        let json = """
            [{"id":31,"routeId":1,"tripDate":"2026-09-28","createdAt":"2026-09-27T22:31:06Z","boardingAttempts":[
              {"id":1,"tripId":31,"routeLegId":2,"attemptSeq":1,"vehicleActualDepartureAt":"2026-09-27T22:43:00Z",
               "result":"MISSED","createdAt":"2026-09-27T22:38:40Z"},
              {"id":2,"tripId":31,"routeLegId":2,"attemptSeq":2,"vehicleActualDepartureAt":"2026-09-27T22:58:00Z",
               "result":"CAUGHT","createdAt":"2026-09-27T22:38:40Z"},
              {"id":3,"tripId":31,"routeLegId":5,"attemptSeq":1,"result":"CAUGHT","createdAt":"2026-09-27T22:38:40Z"}]}]
            """
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            Timestamp.parse(try decoder.singleValueContainer().decode(String.self))!
        }
        let trips = try decoder.decode([CommuteTrip].self, from: Data(json.utf8))
        #expect(ColdStart.samplesByLeg(trips) == [2: 2])
    }
}
