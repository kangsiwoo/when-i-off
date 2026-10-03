import Foundation
import Testing
import WhenIOffKit
import WhenIOffRecorder

@testable import WhenIOff

@Suite("JSONFile")
struct JSONFileTests {
    struct Value: Codable, Equatable {
        var name: String
    }

    @Test func roundTripsAndReportsMissingAsNil() throws {
        let file = JSONFile<Value>(directory: try temporaryDirectory(), name: "value")
        #expect(try file.load() == nil)
        try file.save(Value(name: "a"))
        #expect(try file.load() == Value(name: "a"))
    }

    /// 형식이 맞지 않는 파일은 지우지 않고 옆으로 옮긴다 (ADR 0003 결과).
    @Test func unreadableFileIsMovedAsideNotDeleted() throws {
        let directory = try temporaryDirectory()
        let file = JSONFile<Value>(directory: directory, name: "value")
        try Data("{\"other\":1}".utf8).write(to: file.url)

        #expect(try file.load() == nil)
        #expect(!FileManager.default.fileExists(atPath: file.url.path))
        let moved = AppPaths.unreadableFiles(in: directory)
        #expect(moved.count == 1)
        #expect(moved[0].hasPrefix("value.unreadable-"))
    }

    @Test func outboxStateRoundTripsWithTripIds() throws {
        let directory = try temporaryDirectory()
        var outbox = try Outbox(store: FileOutboxStore(file: JSONFile(directory: directory, name: "outbox")))
        let key = TripKey(routeId: 1, leftHomeAt: Date(timeIntervalSince1970: 1_790_551_800.123))
        try outbox.enqueue(.createTrip(key, tripDate: LocalDate(key.leftHomeAt)), now: Date())
        let dispatch = try #require(try outbox.next(now: Date()))
        try outbox.complete(dispatch.entryId, .tripCreated(id: 31), now: Date())

        let reloaded = try Outbox(store: FileOutboxStore(file: JSONFile(directory: directory, name: "outbox")))
        #expect(reloaded.tripId(for: key) == 31)
        #expect(reloaded.state == outbox.state)
    }
}

@Suite("AppConfiguration")
struct AppConfigurationTests {
    final class MemoryTokenStore: TokenStore {
        var token: String?
        func read() throws -> String? { token }
        func write(_ token: String?) throws { self.token = token }
    }

    @Test func readsBuildSettingsFromInfoPlist() {
        let config = AppConfiguration(infoDictionary: [
            "WIOBaseURL": "http://192.168.0.10:8080", "WIODebugAPIToken": "",
        ])
        #expect(config.baseURL == URL(string: "http://192.168.0.10:8080"))
        #expect(config.debugAPIToken == nil)
    }

    /// xcconfig 값이 비었거나(Secrets.xcconfig 없음) `//` 주석으로 잘렸으면(`http:`) 주소가 없는 것으로 본다.
    @Test func missingOrTruncatedBaseURLIsNil() {
        #expect(AppConfiguration(infoDictionary: [:]).baseURL == nil)
        #expect(AppConfiguration(infoDictionary: ["WIOBaseURL": ""]).baseURL == nil)
        #expect(AppConfiguration(infoDictionary: ["WIOBaseURL": "http:"]).baseURL == nil)
    }

    @Test func debugTokenMovesToTheStoreOnlyOnce() throws {
        let store = MemoryTokenStore()
        let config = AppConfiguration(baseURL: nil, debugAPIToken: "from-xcconfig")
        try config.migrateDebugToken(into: store)
        #expect(store.token == "from-xcconfig")

        store.token = "typed-in-settings"
        try config.migrateDebugToken(into: store)
        #expect(store.token == "typed-in-settings")
    }
}
