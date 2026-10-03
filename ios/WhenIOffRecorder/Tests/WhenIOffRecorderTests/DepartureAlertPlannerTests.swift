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

    func advice(modelVersion: String = "v2", samples: Int?) -> DepartureAdvice {
        DepartureAdvice(
            routeId: 1, recommendedLeaveHomeAt: kst(8, 0), targetArrivalAt: kst(9, 0), catchProbability: 0.9,
            modelVersion: modelVersion, minTransitSampleCount: samples)
    }

    @Test func serverSampleCountWinsOverTheHistoryHeuristic() {
        // 이력으로는 넉넉해도 모델이 기본값으로 내려간 입력을 썼다(0) — 콜드스타트다.
        #expect(ColdStart.isColdStart(advice(samples: 0), transitLegIds: [2], samplesByLeg: [2: 50]))
        #expect(ColdStart.isColdStart(advice(samples: 4), transitLegIds: [2], samplesByLeg: [2: 50]))
        // 이력에는 아직 없어도(보정 행이 다른 밴드를 합친 값) 서버가 5 이상이라면 콜드스타트가 아니다.
        #expect(!ColdStart.isColdStart(advice(samples: 5), transitLegIds: [2], samplesByLeg: [:]))
    }

    @Test func missingServerCountFallsBackToTheHistoryHeuristic() {
        #expect(ColdStart.isColdStart(advice(samples: nil), transitLegIds: [2, 5], samplesByLeg: [2: 9, 5: 4]))
        #expect(!ColdStart.isColdStart(advice(samples: nil), transitLegIds: [2, 5], samplesByLeg: [2: 9, 5: 5]))
        // TRANSIT 구간이 없는 경로는 서버도 값을 주지 않고, 휴리스틱도 콜드스타트로 보지 않는다.
        #expect(!ColdStart.isColdStart(advice(samples: nil), transitLegIds: [], samplesByLeg: [:]))
    }

    @Test func v1IsColdStartEvenWithAServerCount() {
        #expect(ColdStart.isColdStart(advice(modelVersion: "v1", samples: 30), transitLegIds: [2], samplesByLeg: [:]))
    }

    @Test func adviceCarriesTheServerCount() throws {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            Timestamp.parse(try decoder.singleValueContainer().decode(String.self))!
        }
        let json = """
            {"recommendedLeaveHomeAt":"2026-09-27T23:00:00Z","targetArrivalAt":"2026-09-28T00:00:00Z",
             "catchProbability":0.9,"bufferSeconds":300,"modelVersion":"v2","computedAt":"2026-09-27T21:00:00Z",
             "minTransitSampleCount":7}
            """
        let recommendation = try decoder.decode(DepartureRecommendation.self, from: Data(json.utf8))
        #expect(DepartureAdvice(routeId: 1, recommendation) == advice(samples: 7))
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

@Suite("DepartureAlertPlanner.alreadyLeft")
struct AlreadyLeftTests {
    /// 2026-09-28 08:00 출발 권장.
    let advice = DepartureAdvice(
        routeId: 1, recommendedLeaveHomeAt: kst(8, 0), targetArrivalAt: kst(9, 0), catchProbability: 0.9,
        modelVersion: "v2")

    func serverTrip(routeId: Int64, tripDate: String) throws -> CommuteTrip {
        let json = """
            {"id":31,"routeId":\(routeId),"tripDate":"\(tripDate)","createdAt":"2026-09-27T22:31:06Z",
             "boardingAttempts":[]}
            """
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            Timestamp.parse(try decoder.singleValueContainer().decode(String.self))!
        }
        return try decoder.decode(CommuteTrip.self, from: Data(json.utf8))
    }

    @Test func nothingRecordedThatDay() throws {
        let yesterday = try serverTrip(routeId: 1, tripDate: "2026-09-27")
        let otherRoute = try serverTrip(routeId: 2, tripDate: "2026-09-28")
        #expect(
            !DepartureAlertPlanner.alreadyLeft(
                for: advice, serverTrips: [yesterday, otherRoute], recorder: RecorderState()))
    }

    @Test func serverAlreadyHasTheTrip() throws {
        let today = try serverTrip(routeId: 1, tripDate: "2026-09-28")
        #expect(DepartureAlertPlanner.alreadyLeft(for: advice, serverTrips: [today], recorder: RecorderState()))
    }

    /// 지하에서 나서 trip 생성이 아직 outbox에만 있어도 이미 나선 것이다.
    @Test func localTripInProgressCounts() {
        var recorder = Sample.recorder(at: kst(7, 0))
        _ = recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 30)))
        #expect(DepartureAlertPlanner.alreadyLeft(for: advice, serverTrips: [], recorder: recorder.state))
    }

    @Test func localTripEndedThatDayCounts() {
        var state = RecorderState()
        state.lastTripEndedAt[1] = kst(7, 40)
        #expect(DepartureAlertPlanner.alreadyLeft(for: advice, serverTrips: [], recorder: state))
        state.lastTripEndedAt[1] = kst(-5, 0)  // 전날 KST 19:00
        #expect(!DepartureAlertPlanner.alreadyLeft(for: advice, serverTrips: [], recorder: state))
    }
}
