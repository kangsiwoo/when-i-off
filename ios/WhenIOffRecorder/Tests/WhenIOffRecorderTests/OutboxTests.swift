import Foundation
import Testing
import WhenIOffKit

@testable import WhenIOffRecorder

/// 실행기 한 바퀴: 보낼 수 있는 것을 다 보낸다. 보낸 요청 수를 돌려준다.
@discardableResult
func drain(_ outbox: inout Outbox<InMemoryOutboxStore>, _ api: APIClient, now: Date) async throws -> Int {
    var sent = 0
    while let dispatch = try outbox.next(now: now) {
        let outcome = await api.send(dispatch.request)
        try outbox.complete(dispatch.entryId, outcome, now: now)
        sent += 1
    }
    return sent
}

func api(_ backend: FakeBackend) -> APIClient {
    APIClient(
        configuration: APIConfiguration(baseURL: URL(string: "http://localhost:8080")!, apiToken: "test-token"),
        transport: backend
    )
}

@Suite("Outbox")
struct OutboxTests {
    let key = TripKey(routeId: 1, leftHomeAt: kst(7, 31))
    let date = LocalDate(year: 2026, month: 9, day: 28)!

    func attempt(_ seq: Int, arrived: Date? = nil, departed: Date? = nil, result: BoardingResult? = nil)
        -> OutboxCommand
    {
        .upsertAttempt(
            key,
            UpsertBoardingAttemptRequest(
                routeLegId: 2, attemptSeq: seq, arrivedAtStopAt: arrived, vehicleActualDepartureAt: departed,
                result: result))
    }

    // MARK: trip id 해결

    @Test func tripDependentCommandsWaitForTheCreateResponse() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        try outbox.enqueue(attempt(1, arrived: kst(7, 38)), now: kst(7, 38))
        try outbox.enqueue(.uploadGps(key, [GpsPoint(recordedAt: kst(7, 32), lat: 37.2, lng: 127.1)]), now: kst(7, 38))

        let create = try #require(try outbox.next(now: kst(7, 40)))
        #expect(
            create.request == .createTrip(CreateCommuteTripRequest(routeId: 1, tripDate: date, leftHomeAt: kst(7, 31))))
        // 생성 응답 전에는 trip id가 없어 아무것도 못 보낸다 (GPS lane도)
        #expect(try outbox.next(now: kst(7, 40)) == nil)

        try outbox.complete(create.entryId, .tripCreated(id: 31), now: kst(7, 40))
        #expect(outbox.tripId(for: key) == 31)
        let first = try #require(try outbox.next(now: kst(7, 40)))
        let second = try #require(try outbox.next(now: kst(7, 40)))
        #expect(
            first.request
                == .upsertAttempt(
                    tripId: 31, UpsertBoardingAttemptRequest(routeLegId: 2, attemptSeq: 1, arrivedAtStopAt: kst(7, 38)))
        )
        guard case .uploadGps(let batch) = second.request else {
            Issue.record("expected GPS, got \(second.request)")
            return
        }
        #expect(batch.tripId == 31)
    }

    /// 같은 trip의 기록은 넣은 순서대로, 한 번에 하나씩 (서버 순서 규칙 #38).
    @Test func recordLaneIsStrictlyOrdered() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore(seeded(tripId: 31)))
        try outbox.enqueue(attempt(1, departed: kst(7, 43), result: .missed), now: kst(7, 43))
        try outbox.enqueue(attempt(2, departed: kst(7, 58), result: .caught), now: kst(7, 58))

        let first = try #require(try outbox.next(now: kst(8, 0)))
        #expect(try outbox.next(now: kst(8, 0)) == nil)  // 첫 요청이 끝나기 전에는 둘째가 안 나간다
        try outbox.complete(first.entryId, .failed(.transport("lost")), now: kst(8, 0))
        // 첫 요청이 재시도 대기 중이면 둘째도 기다린다 (번호 건너뛰기 400 방지)
        #expect(try outbox.next(now: kst(8, 0)) == nil)
        #expect(outbox.nextWakeAt(now: kst(8, 0)) == kst(8, 0, 5))

        let retried = try #require(try outbox.next(now: kst(8, 0, 5)))
        #expect(retried.entryId == first.entryId)
    }

    // MARK: 오프라인 후 재전송

    /// 지하에서 기록한 출근 전체가 오프라인으로 쌓였다가, 네트워크가 돌아오면 한 번에 올라간다.
    /// 처리됐는데 응답만 잃은 요청을 다시 보내도 서버에는 한 건씩만 남는다.
    @Test func offlineCommuteFlushesIdempotently() async throws {
        let backend = FakeBackend()
        let client = api(backend)
        var outbox = try Outbox(store: InMemoryOutboxStore())
        var recorder = Sample.recorder()

        let effects = recorder.run([
            .regionExited(Sample.homeRegion, at: kst(7, 31)),
            .regionEntered(Sample.dongtanRegion, at: kst(7, 38)),
            .userMissed(legId: nil, attemptSeq: nil, at: kst(7, 43), departedAt: nil, notes: nil),
            .userCaught(legId: nil, attemptSeq: nil, at: kst(7, 58), departedAt: nil),
            .location(LocationSample(recordedAt: kst(8, 0), coordinate: Sample.dongtan.coordinate, accuracyM: 10)),
            .regionEntered(Sample.suseoRegion, at: kst(8, 20)),
            .userCaught(legId: nil, attemptSeq: nil, at: kst(8, 24), departedAt: nil),
            .regionEntered(Sample.officeBusRegion, at: kst(8, 31)),
            .regionEntered(Sample.officeRegion, at: kst(8, 35)),
        ])
        for command in effects.commands { try outbox.enqueue(command, now: kst(8, 35)) }

        // 지하·오프라인: 생성 요청만 시도되고(나머지는 trip id를 기다린다) 재시도 대기로 간다
        await backend.setMode(.offline)
        #expect(try await drain(&outbox, client, now: kst(8, 36)) == 1)
        #expect(outbox.pending.first?.failures == 1)

        // 서버는 처리했지만 응답이 사라짐: 앱은 모른다
        await backend.setMode(.dropResponses)
        #expect(try await drain(&outbox, client, now: kst(8, 37)) == 1)
        #expect(await backend.trips.count == 1)
        #expect(outbox.tripId(for: key) == nil)

        // 복구: 같은 생성 요청이 200으로 같은 trip을 돌려주고, 줄 서 있던 기록이 차례로 올라간다
        await backend.setMode(.online)
        try outbox.expediteRetries()
        try await drain(&outbox, client, now: kst(8, 38))
        #expect(outbox.pending.isEmpty)
        #expect(outbox.deadLetters.isEmpty)

        let trips = await backend.trips
        #expect(trips.count == 1)
        let tripId = try #require(trips.first?.id)
        #expect(outbox.tripId(for: key) == tripId)
        #expect(trips[0].arrivedDestinationAt == "2026-09-27T23:35:00.000Z")
        #expect(await backend.attemptCount(trip: tripId) == 3)  // GTX 1(놓침)·2(탐), 버스 1
        #expect(await backend.attemptField(trip: tripId, leg: 2, seq: 1, "result") == "MISSED")
        #expect(
            await backend.attemptField(trip: tripId, leg: 2, seq: 1, "arrivedAtStopAt") == "2026-09-27T22:38:00.000Z")
        #expect(await backend.attemptField(trip: tripId, leg: 2, seq: 2, "result") == "CAUGHT")
        #expect(await backend.attemptField(trip: tripId, leg: 2, seq: 2, "alightedAt") == "2026-09-27T23:20:00.000Z")
        #expect(await backend.attemptField(trip: tripId, leg: 5, seq: 1, "alightedAt") == "2026-09-27T23:31:00.000Z")
        #expect(await backend.gpsTimes.count == 1)

        // 같은 명령을 통째로 한 번 더 보내도 서버 상태는 그대로다
        for command in effects.commands { try outbox.enqueue(command, now: kst(9, 0)) }
        try await drain(&outbox, client, now: kst(9, 0))
        #expect(await backend.trips.count == 1)
        #expect(await backend.attemptCount(trip: tripId) == 3)
        #expect(await backend.gpsTimes.count == 1)
    }

    /// 보내던 중에 앱이 죽으면 결과를 모른다. 다시 시작하면 그 요청을 다시 보낸다.
    @Test func inFlightEntriesAreResentAfterRelaunch() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        let sent = try #require(try outbox.next(now: kst(7, 31)))
        #expect(outbox.pending.first?.inFlight == true)

        var relaunched = try Outbox(store: outbox.store)
        let again = try #require(try relaunched.next(now: kst(7, 32)))
        #expect(again == sent)
    }

    // MARK: 거부 (400)

    /// 400은 다시 보내도 같다. 버리고 기록 확인 화면에 띄운다. 같은 lane의 다음 명령은 계속 간다.
    @Test func rejectedAttemptIsDeadLetteredAndTheLaneContinues() async throws {
        let backend = FakeBackend()
        let client = api(backend)
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        // 번호를 건너뛴 시도 (seq 1 없이 2) → 서버 400
        try outbox.enqueue(attempt(2, departed: kst(7, 58), result: .caught), now: kst(7, 58))
        try outbox.enqueue(
            .updateTrip(key, UpdateCommuteTripRequest(arrivedDestinationAt: kst(8, 35))), now: kst(8, 35))

        try await drain(&outbox, client, now: kst(8, 36))
        #expect(outbox.pending.isEmpty)
        #expect(outbox.deadLetters.count == 1)
        #expect(outbox.deadLetters[0].command == attempt(2, departed: kst(7, 58), result: .caught))
        #expect(outbox.deadLetters[0].reason == .rejected(status: 400, message: "attemptSeq 2 skips a number"))
        #expect(await backend.trips.first?.arrivedDestinationAt == "2026-09-27T23:35:00.000Z")
    }

    /// trip 생성이 거부되면(예: 409 tripDate 불일치) 그 trip의 기록은 보낼 곳이 없다. 함께 버린다.
    @Test func rejectedCreateTakesItsDependentsWithIt() async throws {
        let backend = FakeBackend()
        await backend.fail("POST", "/commute-trips", status: 409)
        var outbox = try Outbox(store: InMemoryOutboxStore())
        let other = TripKey(routeId: 2, leftHomeAt: kst(18, 30))
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        try outbox.enqueue(attempt(1, arrived: kst(7, 38)), now: kst(7, 38))
        try outbox.enqueue(.uploadGps(key, [GpsPoint(recordedAt: kst(7, 32), lat: 37.2, lng: 127.1)]), now: kst(7, 38))
        try outbox.enqueue(.createTrip(other, tripDate: date), now: kst(18, 30))

        try await drain(&outbox, api(backend), now: kst(18, 31))
        #expect(outbox.pending.isEmpty)
        #expect(
            outbox.deadLetters.map(\.reason) == [
                .rejected(status: 409, message: "forced"), .tripRejected(key), .tripRejected(key),
            ])
        #expect(outbox.tripId(for: other) != nil)  // 다른 trip은 영향 없음
    }

    @Test func unauthorizedPausesEverythingUntilResumed() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        let sent = try #require(try outbox.next(now: kst(7, 31)))
        try outbox.complete(sent.entryId, .failed(.http(status: 401, problem: nil, body: nil)), now: kst(7, 31))
        #expect(outbox.state.paused == .unauthorized)
        #expect(try outbox.next(now: kst(9, 0)) == nil)
        #expect(outbox.pending.count == 1)  // 지우지 않는다

        try outbox.resume()
        #expect(try outbox.next(now: kst(9, 0)) != nil)
    }

    // MARK: 합치기·나누기

    @Test func pendingUpsertsForTheSameAttemptAreMerged() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore(seeded(tripId: 31)))
        try outbox.enqueue(attempt(1, arrived: kst(7, 38)), now: kst(7, 38))
        try outbox.enqueue(attempt(1, departed: kst(7, 45), result: .caught), now: kst(7, 45))
        #expect(outbox.pending.count == 1)
        #expect(outbox.pending[0].command == attempt(1, arrived: kst(7, 38), departed: kst(7, 45), result: .caught))

        // 보내는 중인 항목에는 합치지 않는다 (응답이 오면 지워지므로 새 값이 사라진다)
        _ = try outbox.next(now: kst(7, 46))
        try outbox.enqueue(
            .upsertAttempt(key, UpsertBoardingAttemptRequest(routeLegId: 2, attemptSeq: 1, alightedAt: kst(8, 5))),
            now: kst(8, 5))
        #expect(outbox.pending.count == 2)

        // 다른 시도는 합치지 않는다
        try outbox.enqueue(attempt(2, departed: kst(7, 58)), now: kst(8, 6))
        #expect(outbox.pending.count == 3)
    }

    @Test func largeGpsBatchesAreSplitAtTheServerLimit() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore())
        let points = (0..<1_200).map {
            GpsPoint(recordedAt: kst(7, 31, 0).addingTimeInterval(Double($0)), lat: 37.2, lng: 127.1)
        }
        try outbox.enqueue(.uploadGps(key, points), now: kst(8, 0))
        let sizes = outbox.pending.map { entry -> Int in
            if case .uploadGps(_, let batch) = entry.command { return batch.count }
            return 0
        }
        #expect(sizes == [500, 500, 200])
    }

    // MARK: 취소 (#88)

    /// 생성이 한 번도 나가지 않았으면 서버에는 아무것도 없다. 버리기만 하고 삭제는 넣지 않는다.
    @Test func discardBeforeTheCreateWasSentOnlyDropsCommands() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore())
        let other = TripKey(routeId: 2, leftHomeAt: kst(18, 30))
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        try outbox.enqueue(attempt(1, arrived: kst(7, 38)), now: kst(7, 38))
        try outbox.enqueue(.createTrip(other, tripDate: date), now: kst(18, 30))
        try outbox.discard(trip: key, now: kst(18, 31))
        #expect(outbox.pending.map(\.command) == [.createTrip(other, tripDate: date)])
    }

    /// 서버에 만들어진 trip을 취소하면 대기 명령을 버리고 삭제를 보낸다. 지운 trip의 id 매핑도 지운다.
    @Test func discardOfACreatedTripDeletesItOnTheServer() async throws {
        let backend = FakeBackend()
        let client = api(backend)
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        try await drain(&outbox, client, now: kst(7, 31))
        let tripId = try #require(outbox.tripId(for: key))

        // 오프라인에서 쌓인 기록은 서버에 갈 필요가 없다
        try outbox.enqueue(attempt(1, arrived: kst(7, 38)), now: kst(7, 38))
        try outbox.enqueue(.uploadGps(key, [GpsPoint(recordedAt: kst(7, 32), lat: 37.2, lng: 127.1)]), now: kst(7, 38))
        try outbox.discard(trip: key, now: kst(7, 40))
        #expect(outbox.pending.map(\.command) == [.deleteTrip(key)])

        let dispatch = try #require(try outbox.next(now: kst(7, 40)))
        #expect(dispatch.request == .deleteTrip(tripId: tripId))
        try outbox.complete(dispatch.entryId, await client.send(dispatch.request), now: kst(7, 40))
        #expect(outbox.pending.isEmpty)
        #expect(outbox.deadLetters.isEmpty)
        #expect(outbox.tripId(for: key) == nil)
        #expect(await backend.trips.isEmpty)
        #expect(await backend.gpsTimes.isEmpty)

        // 두 번 취소해도 삭제는 하나다
        try outbox.discard(trip: key, now: kst(7, 41))
        #expect(outbox.pending.isEmpty)
    }

    /// 삭제가 응답만 잃었으면 다시 보낸다. 서버는 이미 지워 404를 주고, 그것을 성공으로 본다 (DeadLetter 없음).
    @Test func deleteThatLostItsResponseTreats404AsDone() async throws {
        let backend = FakeBackend()
        let client = api(backend)
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        try await drain(&outbox, client, now: kst(7, 31))
        try outbox.discard(trip: key, now: kst(7, 32))

        await backend.setMode(.dropResponses)
        #expect(try await drain(&outbox, client, now: kst(7, 33)) == 1)
        #expect(await backend.trips.isEmpty)
        #expect(outbox.pending.first?.failures == 1)

        await backend.setMode(.online)
        try outbox.expediteRetries()
        try await drain(&outbox, client, now: kst(7, 34))
        #expect(outbox.pending.isEmpty)
        #expect(outbox.deadLetters.isEmpty)
        #expect(outbox.tripId(for: key) == nil)
    }

    /// 생성 요청이 처리됐는데 응답만 잃은 채 취소됐다. 서버에 trip이 있을 수 있으므로 생성 명령은 남겨 id를 알아내고
    /// (재전송은 같은 trip을 200으로 돌려준다) 그 id로 지운다.
    @Test func discardAfterAnUnansweredCreateStillDeletesTheServerTrip() async throws {
        let backend = FakeBackend()
        let client = api(backend)
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        try outbox.enqueue(attempt(1, arrived: kst(7, 38)), now: kst(7, 38))
        await backend.setMode(.dropResponses)
        try await drain(&outbox, client, now: kst(7, 39))
        #expect(await backend.trips.count == 1)
        #expect(outbox.tripId(for: key) == nil)

        try outbox.discard(trip: key, now: kst(7, 40))
        #expect(outbox.pending.map(\.command) == [.createTrip(key, tripDate: date), .deleteTrip(key)])

        await backend.setMode(.online)
        try outbox.expediteRetries()
        try await drain(&outbox, client, now: kst(7, 41))
        #expect(outbox.pending.isEmpty)
        #expect(outbox.deadLetters.isEmpty)
        #expect(await backend.trips.isEmpty)
        #expect(await backend.attemptCount(trip: 31) == 0)
    }

    /// 보내는 중인 요청은 되돌릴 수 없다. 삭제는 그 trip의 보내는 중인 요청이 모두 끝난 뒤에 나간다 — GPS lane 포함.
    @Test func deleteWaitsForInFlightCommandsOfThatTrip() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore(seeded(tripId: 31)))
        try outbox.enqueue(attempt(1, arrived: kst(7, 38)), now: kst(7, 38))
        try outbox.enqueue(.uploadGps(key, [GpsPoint(recordedAt: kst(7, 32), lat: 37.2, lng: 127.1)]), now: kst(7, 38))
        try outbox.enqueue(attempt(2, departed: kst(7, 45), result: .caught), now: kst(7, 45))
        let upsert = try #require(try outbox.next(now: kst(7, 46)))
        let gps = try #require(try outbox.next(now: kst(7, 46)))

        try outbox.discard(trip: key, now: kst(7, 47))
        #expect(outbox.pending.map(\.id) == [upsert.entryId, gps.entryId, 4])
        #expect(try outbox.next(now: kst(7, 47)) == nil)

        try outbox.complete(upsert.entryId, .delivered, now: kst(7, 47))
        #expect(try outbox.next(now: kst(7, 47)) == nil)  // GPS가 아직 가는 중

        try outbox.complete(gps.entryId, .delivered, now: kst(7, 47))
        #expect(try outbox.next(now: kst(7, 47))?.request == .deleteTrip(tripId: 31))
    }

    /// 보내던 생성이 거부되면 서버에 trip이 없다. 삭제는 거부 기록 없이 사라진다.
    @Test func deleteOfARejectedCreateIsDroppedSilently() async throws {
        let backend = FakeBackend()
        await backend.fail("POST", "/commute-trips", status: 409)
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        let create = try #require(try outbox.next(now: kst(7, 31)))
        try outbox.discard(trip: key, now: kst(7, 32))
        #expect(outbox.pending.map(\.command) == [.createTrip(key, tripDate: date), .deleteTrip(key)])

        try outbox.complete(create.entryId, await api(backend).send(create.request), now: kst(7, 32))
        #expect(outbox.pending.isEmpty)
        #expect(outbox.deadLetters.map(\.reason) == [.rejected(status: 409, message: "forced")])
    }

    /// 업데이트 전 앱이 저장한 outbox 파일(합성 `Codable` 형식, 날짜는 기준일 이후 초)이 그대로 읽힌다.
    /// 읽은 뒤 취소하면 삭제가 붙는다.
    @Test func outboxSavedBeforeDeleteTripStillDecodes() throws {
        let trip = #"{"leftHomeAt":812241060,"routeId":1}"#
        let json = """
            {"deadLetters":[],"entries":[
            {"command":{"createTrip":{"_0":\(trip),"tripDate":"2026-09-28"}},"enqueuedAt":812241060,"failures":1,
             "id":1,"inFlight":false,"notBefore":812241065},
            {"command":{"upsertAttempt":{"_0":\(trip),"_1":{"arrivedAtStopAt":812241480,"attemptSeq":1,"routeLegId":2}}},
             "enqueuedAt":812241480,"failures":0,"id":2,"inFlight":false},
            {"command":{"uploadGps":{"_0":\(trip),"_1":[{"lat":37.2,"lng":127.1,"recordedAt":812241120}]}},
             "enqueuedAt":812241480,"failures":0,"id":3,"inFlight":false},
            {"command":{"updateTrip":{"_0":\(trip),"_1":{"arrivedDestinationAt":812244900}}},
             "enqueuedAt":812244900,"failures":0,"id":4,"inFlight":true}
            ],"nextId":5,"tripIds":[{"leftHomeAt":812230000,"routeId":2},77]}
            """
        let saved = try JSONDecoder().decode(OutboxState.self, from: Data(json.utf8))
        #expect(
            saved.entries.map(\.command) == [
                .createTrip(key, tripDate: date),
                attempt(1, arrived: kst(7, 38)),
                .uploadGps(key, [GpsPoint(recordedAt: kst(7, 32), lat: 37.2, lng: 127.1)]),
                .updateTrip(key, UpdateCommuteTripRequest(arrivedDestinationAt: kst(8, 35))),
            ])
        let otherKey = TripKey(routeId: 2, leftHomeAt: Date(timeIntervalSinceReferenceDate: 812_230_000))
        #expect(saved.tripIds == [otherKey: 77])

        // 앱 재시작: 보내던 중이던 도착은 다시 보낼 대상이 된다. 생성은 한 번 실패한 적 있다(결과 모름)
        var outbox = try Outbox(store: InMemoryOutboxStore(saved))
        try outbox.discard(trip: key, now: kst(8, 40))
        #expect(outbox.pending.map(\.command) == [.createTrip(key, tripDate: date), .deleteTrip(key)])
        let reloaded = try JSONDecoder().decode(OutboxState.self, from: JSONEncoder().encode(outbox.state))
        #expect(reloaded == outbox.state)
    }

    @Test func everyChangeIsPersisted() throws {
        var outbox = try Outbox(store: InMemoryOutboxStore())
        try outbox.enqueue(.createTrip(key, tripDate: date), now: kst(7, 31))
        #expect(outbox.store.saved == outbox.state)
        let reloaded = try Outbox(store: outbox.store)
        #expect(reloaded.pending == outbox.pending)
    }

    private func seeded(tripId: Int64) -> OutboxState {
        var state = OutboxState()
        state.tripIds[key] = tripId
        return state
    }
}

@Suite("RetryPolicy")
struct RetryPolicyTests {
    let policy = RetryPolicy()
    let create = OutboxCommand.createTrip(
        TripKey(routeId: 1, leftHomeAt: kst(7, 31)), tripDate: LocalDate(year: 2026, month: 9, day: 28)!)
    let gps = OutboxCommand.uploadGps(nil, [])

    @Test func backsOffExponentiallyUpToFifteenMinutes() {
        #expect((1...9).map { policy.delay(afterFailures: $0) } == [5, 10, 20, 40, 80, 160, 320, 640, 900])
        #expect(policy.delay(afterFailures: 1_000) == 900)
        #expect(policy.delay(afterFailures: 0) == 0)
    }

    @Test(arguments: [408, 425, 429, 500, 502, 503])
    func transientStatusesAreRetried(status: Int) {
        #expect(policy.decide(.http(status: status, problem: nil, body: nil), for: gps) == .retry)
    }

    @Test(arguments: [400, 404, 409, 422])
    func clientErrorsAreRejected(status: Int) {
        #expect(policy.decide(.http(status: status, problem: nil, body: nil), for: gps) == .reject)
    }

    /// 지우려던 trip이 이미 없다 = 원하던 결과 (#88). 다른 거부는 그대로 거부다.
    @Test func deleteOfAMissingTripIsDone() {
        let delete = OutboxCommand.deleteTrip(TripKey(routeId: 1, leftHomeAt: kst(7, 31)))
        #expect(policy.decide(.http(status: 404, problem: nil, body: nil), for: delete) == .treatAsDelivered)
        #expect(policy.decide(.http(status: 409, problem: nil, body: nil), for: delete) == .reject)
        #expect(policy.decide(.http(status: 503, problem: nil, body: nil), for: delete) == .retry)
    }

    @Test func classifiesTheRest() {
        #expect(policy.decide(.transport("timeout"), for: gps) == .retry)
        #expect(policy.decide(.http(status: 401, problem: nil, body: nil), for: gps) == .pause)
        #expect(policy.decide(.invalidRequest("too many"), for: gps) == .reject)
        // 2xx인데 본문을 못 읽음: 서버에는 반영됐다. 다만 생성은 trip id가 필요하다
        #expect(policy.decide(.decoding("x"), for: gps) == .treatAsDelivered)
        #expect(policy.decide(.decoding("x"), for: create) == .reject)
    }
}
