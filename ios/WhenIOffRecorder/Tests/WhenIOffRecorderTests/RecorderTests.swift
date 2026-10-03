import Foundation
import Testing
import WhenIOffKit

@testable import WhenIOffRecorder

@Suite("Recorder")
struct RecorderTests {
    let gtx = Sample.gtxLegId
    let bus = Sample.busLegId
    let tripKey = TripKey(routeId: 1, leftHomeAt: kst(7, 31))

    // MARK: 정상 출근

    @Test func normalCommuteProducesTheTripAttemptsAndArrival() throws {
        var recorder = Sample.recorder()

        let start = recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 31)))
        #expect(
            start == [
                .enqueue(.createTrip(tripKey, tripDate: LocalDate(year: 2026, month: 9, day: 28)!)),
                .startLocationUpdates,
            ])

        let atStop = recorder.handle(.regionEntered(Sample.dongtanRegion, at: kst(7, 38)))
        #expect(
            atStop.upserts == [
                UpsertBoardingAttemptRequest(routeLegId: gtx, attemptSeq: 1, arrivedAtStopAt: kst(7, 38))
            ])
        #expect(atStop.prompts.map(\.stopName) == ["동탄"])
        #expect(atStop.prompts.map(\.attemptSeq) == [1])

        let caught = recorder.handle(.userCaught(legId: gtx, attemptSeq: 1, at: kst(7, 45), departedAt: nil))
        #expect(
            caught == [
                .enqueue(
                    .upsertAttempt(
                        tripKey,
                        UpsertBoardingAttemptRequest(
                            routeLegId: gtx, attemptSeq: 1, vehicleActualDepartureAt: kst(7, 45), result: .caught))),
                .dismissBoardingPrompt(legId: gtx),
            ])

        // 수서: GTX 하차와 버스 승차가 한 지역 — 하차가 먼저
        let transfer = recorder.handle(.regionEntered(Sample.suseoRegion, at: kst(8, 5)))
        #expect(
            transfer.upserts == [
                UpsertBoardingAttemptRequest(routeLegId: gtx, attemptSeq: 1, alightedAt: kst(8, 5)),
                UpsertBoardingAttemptRequest(routeLegId: bus, attemptSeq: 1, arrivedAtStopAt: kst(8, 5)),
            ])
        #expect(transfer.prompts.map(\.lineName) == ["402"])

        _ = recorder.handle(.userCaught(legId: bus, attemptSeq: 1, at: kst(8, 9), departedAt: nil))
        let alight = recorder.handle(.regionEntered(Sample.officeBusRegion, at: kst(8, 15)))
        #expect(
            alight.upserts == [UpsertBoardingAttemptRequest(routeLegId: bus, attemptSeq: 1, alightedAt: kst(8, 15))])

        let arrive = recorder.handle(.regionEntered(Sample.officeRegion, at: kst(8, 20)))
        #expect(
            arrive == [
                .enqueue(.updateTrip(tripKey, UpdateCommuteTripRequest(arrivedDestinationAt: kst(8, 20)))),
                .stopLocationUpdates,
                .tripEnded(tripKey),
            ])
        #expect(recorder.state.trip == nil)
        let all = start + atStop + caught + transfer + alight + arrive
        #expect(all.reviews.isEmpty)
    }

    // MARK: 놓친 뒤 탐

    @Test func missedThenCaughtRecordsTwoAttemptsOnTheSameLeg() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
        ])

        let missed = recorder.handle(
            .userMissed(legId: gtx, attemptSeq: 1, at: kst(7, 43), departedAt: nil, notes: "만석 통과"))
        #expect(
            missed.upserts == [
                UpsertBoardingAttemptRequest(
                    routeLegId: gtx, attemptSeq: 1, vehicleActualDepartureAt: kst(7, 43), result: .missed,
                    notes: "만석 통과")
            ])
        #expect(missed.prompts.map(\.attemptSeq) == [2])

        // 첫 알림의 버튼을 뒤늦게 누르면 무시 (이미 끝난 시도)
        #expect(recorder.handle(.userCaught(legId: gtx, attemptSeq: 1, at: kst(7, 50), departedAt: nil)).isEmpty)

        let caught = recorder.handle(.userCaught(legId: gtx, attemptSeq: 2, at: kst(7, 58), departedAt: nil))
        // 뒤 시도에는 정류장 도착을 싣지 않는다 (#42)
        #expect(
            caught.upserts == [
                UpsertBoardingAttemptRequest(
                    routeLegId: gtx, attemptSeq: 2, vehicleActualDepartureAt: kst(7, 58), result: .caught)
            ])

        let alight = recorder.handle(.regionEntered(Sample.suseoRegion, at: kst(8, 20)))
        #expect(
            alight.upserts.first == UpsertBoardingAttemptRequest(routeLegId: gtx, attemptSeq: 2, alightedAt: kst(8, 20))
        )
    }

    /// 뒤 시도의 출발이 앞 시도보다 이르면 서버가 400을 준다(#38). 그 값은 빼고 결과만 보내고 확인 요청.
    @Test func laterAttemptDepartingBeforeTheMissedOneIsNotSent() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
            .userMissed(legId: nil, attemptSeq: nil, at: kst(7, 43), departedAt: nil, notes: nil),
        ])
        let caught = recorder.handle(.userCaught(legId: nil, attemptSeq: nil, at: kst(7, 58), departedAt: kst(7, 40)))
        #expect(caught.upserts == [UpsertBoardingAttemptRequest(routeLegId: gtx, attemptSeq: 2, result: .caught)])
        #expect(caught.reviews == [.timeOutOfOrder])
    }

    @Test func attemptSeqStopsAtTheServerLimit() {
        var recorder = Sample.recorder()
        recorder.policy.maxAttemptSeq = 2
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
            .userMissed(legId: nil, attemptSeq: nil, at: kst(7, 43), departedAt: nil, notes: nil),
        ])
        let last = recorder.handle(
            .userMissed(legId: nil, attemptSeq: nil, at: kst(7, 50), departedAt: nil, notes: nil))
        #expect(last.upserts.map(\.attemptSeq) == [2])
        #expect(last.reviews == [.attemptLimitReached])
        #expect(last.prompts.isEmpty)
    }

    // MARK: 이벤트 누락·역순

    /// 지하에서 승차역 진입이 안 잡혔다: 하차역 진입만으로 "탔음" 시도를 만든다 (도착·출발 시각은 비어 있다).
    @Test func alightEntryWithoutBoardEntryRecordsAnInferredCatch() {
        var recorder = Sample.recorder()
        _ = recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 31)))
        let effects = recorder.handle(.regionEntered(Sample.suseoRegion, at: kst(8, 5)))
        #expect(
            effects.upserts == [
                UpsertBoardingAttemptRequest(routeLegId: gtx, attemptSeq: 1, alightedAt: kst(8, 5), result: .caught),
                UpsertBoardingAttemptRequest(routeLegId: bus, attemptSeq: 1, arrivedAtStopAt: kst(8, 5)),
            ])
        #expect(effects.reviews == [.boardStopMissed])
    }

    @Test func reachingTheAlightStopWithoutAnswerInfersCaught() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
        ])
        let effects = recorder.handle(.regionEntered(Sample.suseoRegion, at: kst(8, 5)))
        #expect(effects.contains(.dismissBoardingPrompt(legId: gtx)))
        #expect(
            effects.upserts.first
                == UpsertBoardingAttemptRequest(routeLegId: gtx, attemptSeq: 1, alightedAt: kst(8, 5), result: .caught))
        #expect(effects.reviews == [.caughtInferred])
    }

    /// 버스 구간 지점을 하나도 못 잡고 회사에 도착: GTX 하차 누락, 버스 구간 건너뜀을 알리고 도착은 기록한다.
    @Test func arrivalClosesOpenLegs() {
        var recorder = Sample.recorder()
        let effects = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
            .userCaught(legId: nil, attemptSeq: nil, at: kst(7, 45), departedAt: nil),
            .regionEntered(Sample.officeRegion, at: kst(8, 20)),
        ])
        #expect(effects.reviews == [.alightMissed, .legSkipped])
        #expect(
            effects.commands.last == .updateTrip(tripKey, UpdateCommuteTripRequest(arrivedDestinationAt: kst(8, 20))))
    }

    /// 도착 뒤 늦게 온 하차역·승차역 진입은 새 trip을 만들지 않는다.
    @Test func lateEventsAfterArrivalAreDropped() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.officeRegion, at: kst(8, 20)),
        ])
        #expect(recorder.handle(.regionEntered(Sample.officeBusRegion, at: kst(8, 21))).isEmpty)
        #expect(recorder.handle(.regionEntered(Sample.dongtanRegion, at: kst(8, 22))).isEmpty)
        #expect(recorder.state.trip == nil)
    }

    /// 집을 나서기 전 시각의 정류장 진입은 trip 범위(#37) 밖이라 보내지 않는다.
    @Test func eventsBeforeLeavingHomeAreDropped() {
        var recorder = Sample.recorder()
        _ = recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 31)))
        let effects = recorder.handle(.regionEntered(Sample.dongtanRegion, at: kst(7, 30)))
        #expect(effects.commands.isEmpty)
        #expect(effects.reviews == [.timeOutOfOrder])
    }

    /// 이미 보낸 출발 시각보다 이른 도착은 서버가 거부한다. 도착을 빼고 trip을 끝낸다.
    @Test func arrivalBeforeRecordedTimesIsNotSent() {
        var recorder = Sample.recorder()
        let effects = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
            .userCaught(legId: nil, attemptSeq: nil, at: kst(7, 45), departedAt: kst(8, 30)),
            .regionEntered(Sample.officeRegion, at: kst(8, 20)),
        ])
        #expect(!effects.commands.contains { if case .updateTrip = $0 { return true } else { return false } })
        #expect(effects.reviews.contains(.timeOutOfOrder))
        #expect(effects.contains(.tripEnded(tripKey)))
    }

    /// 앱이 꺼져 있어 집 이탈을 놓쳤다: 첫 승차역 진입 시각으로 trip을 시작한다 (멱등 키가 필요하다).
    @Test func missedHomeExitStartsTheTripAtTheFirstStop() {
        var recorder = Sample.recorder()
        let effects = recorder.handle(.regionEntered(Sample.dongtanRegion, at: kst(7, 38)))
        let key = TripKey(routeId: 1, leftHomeAt: kst(7, 38))
        #expect(effects.commands.first == .createTrip(key, tripDate: LocalDate(year: 2026, month: 9, day: 28)!))
        #expect(
            effects.upserts == [
                UpsertBoardingAttemptRequest(routeLegId: gtx, attemptSeq: 1, arrivedAtStopAt: kst(7, 38))
            ])
        #expect(effects.reviews == [.leftHomeApproximated])
    }

    @Test func regionJitterDoesNotRewriteTheFirstArrival() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
        ])
        #expect(recorder.handle(.regionEntered(Sample.dongtanRegion, at: kst(7, 39))).isEmpty)
        #expect(recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 40))).isEmpty)
    }

    // MARK: 출근/퇴근 판별

    @Test func leavingTheOfficeInTheEveningStartsTheHomeRoute() {
        var recorder = Sample.recorder(at: kst(18, 0))
        let office = Sample.region(.origin(routeId: 2), in: recorder.plan)
        let effects = recorder.handle(.regionExited(office, at: kst(18, 30)))
        #expect(
            effects.commands == [
                .createTrip(
                    TripKey(routeId: 2, leftHomeAt: kst(18, 30)), tripDate: LocalDate(year: 2026, month: 9, day: 28)!)
            ])
        #expect(recorder.state.trip?.route.direction == .toHome)
    }

    /// 아침에 회사를 나서도 회사가 출발지인 경로(퇴근)다. 평소와 달라 확인 알림을 띄운다.
    @Test func unusualHourStartsByRegionAndAsksForConfirmation() {
        var recorder = Sample.recorder()
        let office = Sample.region(.origin(routeId: 2), in: recorder.plan)
        let effects = recorder.handle(.regionExited(office, at: kst(8, 0)))
        let key = TripKey(routeId: 2, leftHomeAt: kst(8, 0))
        #expect(effects.contains(.confirmTripStart(key, .toHome)))
        #expect(effects.reviews == [.unusualDirection])

        // 지난 trip의 알림에 남은 취소 버튼은 지금 기록을 건드리지 않는다.
        let stale = TripKey(routeId: 2, leftHomeAt: kst(-6, 0))
        #expect(recorder.handle(.userCancelledTrip(stale, at: kst(8, 1))).isEmpty)
        #expect(recorder.state.trip?.key == key)

        let cancel = recorder.handle(.userCancelledTrip(key, at: kst(8, 1)))
        #expect(cancel == [.discardOutbox(key), .stopLocationUpdates, .tripEnded(key)])
        #expect(recorder.state.trip == nil)
    }

    /// KST 23:50 퇴근은 그날, 00:10 퇴근은 다음날 날짜다 (UTC가 아니라 KST 달력).
    @Test func tripDateIsTheKoreanCalendarDay() {
        var recorder = Sample.recorder(at: kst(23, 0))
        let office = Sample.region(.origin(routeId: 2), in: recorder.plan)
        let effects = recorder.handle(.regionExited(office, at: kst(24, 10)))
        #expect(
            effects.commands == [
                .createTrip(
                    TripKey(routeId: 2, leftHomeAt: kst(24, 10)), tripDate: LocalDate(year: 2026, month: 9, day: 29)!)
            ])
    }

    // MARK: 집에 다시 들어옴

    @Test func returningHomeBeforeTheFirstStopMovesLeftHomeAt() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.homeRegion, at: kst(7, 33)),
        ])
        let again = recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 36)))
        // 키는 처음 값 그대로 (생성 재전송이 같은 trip을 찾도록), 서버 값만 PATCH
        #expect(again.commands == [.updateTrip(tripKey, UpdateCommuteTripRequest(leftHomeAt: kst(7, 36)))])
        #expect(recorder.state.trip?.leftHomeAt == kst(7, 36))

        // 이제 7:35의 정류장 진입은 trip 범위 밖
        #expect(recorder.handle(.regionEntered(Sample.dongtanRegion, at: kst(7, 35))).reviews == [.timeOutOfOrder])
    }

    @Test func stayingHomeAbandonsTheTrip() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.homeRegion, at: kst(7, 33)),
        ])
        #expect(recorder.handle(.tick(kst(7, 50))).isEmpty)
        let effects = recorder.handle(.tick(kst(8, 3)))
        #expect(
            effects == [
                .needsReview(ReviewNote(tripKey: tripKey, legId: nil, kind: .tripAbandoned, at: kst(8, 3))),
                .stopLocationUpdates, .tripEnded(tripKey),
            ])
    }

    @Test func tripTimesOutWithoutArrival() {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
        ])
        let effects = recorder.handle(.tick(kst(11, 31)))
        #expect(effects.reviews == [.caughtInferred, .legSkipped, .tripTimedOut])
        #expect(effects.contains(.tripEnded(tripKey)))
    }

    // MARK: GPS

    @Test func gpsIsFilteredAndBatchedOnlyDuringATrip() {
        var recorder = Sample.recorder()
        let idle = recorder.handle(
            .location(LocationSample(recordedAt: kst(7, 0), coordinate: Sample.home, accuracyM: 5)))
        #expect(idle.isEmpty)

        _ = recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 31)))
        var effects: [RecorderEffect] = []
        for second in 0..<29 {
            let accuracy: Double? = second == 3 ? 150 : (second == 4 ? nil : 8)
            effects += recorder.handle(
                .location(LocationSample(recordedAt: kst(7, 32, second), coordinate: Sample.home, accuracyM: accuracy)))
        }
        #expect(effects.isEmpty)

        // 첫 점에서 30초가 지나면 묶어 올린다. 부정확한 점(150m, 없음)은 빠진다
        let flush = recorder.handle(
            .location(LocationSample(recordedAt: kst(7, 32, 30), coordinate: Sample.home, accuracyM: 8)))
        guard case .enqueue(.uploadGps(let key, let points)) = flush.first else {
            Issue.record("expected a GPS batch, got \(flush)")
            return
        }
        #expect(key == tripKey)
        #expect(points.count == 28)
        #expect(points.allSatisfy { ($0.accuracyM ?? 999) <= 100 })
    }

    @Test func gpsFlushesOnCountTickAndArrival() {
        var recorder = Sample.recorder()
        recorder.policy.gpsBatchSize = 3
        _ = recorder.handle(.regionExited(Sample.homeRegion, at: kst(7, 31)))
        func sample(_ second: Int) -> RecorderEvent {
            .location(LocationSample(recordedAt: kst(7, 32, second), coordinate: Sample.home, accuracyM: 10))
        }
        let byCount = recorder.run([sample(0), sample(1), sample(2)])
        #expect(byCount.commands.count == 1)

        _ = recorder.handle(sample(3))
        #expect(recorder.handle(.tick(kst(7, 32, 20))).isEmpty)
        #expect(recorder.handle(.tick(kst(7, 32, 33))).commands.count == 1)

        _ = recorder.handle(sample(40))
        let arrive = recorder.handle(.regionEntered(Sample.officeRegion, at: kst(8, 20)))
        #expect(arrive.commands.contains { if case .uploadGps = $0 { return true } else { return false } })
    }

    // MARK: 재시작

    /// 앱이 기록 도중 종료돼도 상태를 저장했다가 이어서 기록한다.
    @Test func stateSurvivesARelaunch() throws {
        var recorder = Sample.recorder()
        _ = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
            .userMissed(legId: nil, attemptSeq: nil, at: kst(7, 43), departedAt: nil, notes: nil),
        ])
        let saved = try JSONEncoder().encode(recorder.state)
        let restored = try JSONDecoder().decode(RecorderState.self, from: saved)
        #expect(restored == recorder.state)

        var relaunched = Recorder(routes: Sample.routes, plan: Sample.plan(), state: restored)
        let caught = relaunched.handle(.userCaught(legId: gtx, attemptSeq: 2, at: kst(7, 58), departedAt: nil))
        #expect(caught.upserts.map(\.attemptSeq) == [2])
    }
}
