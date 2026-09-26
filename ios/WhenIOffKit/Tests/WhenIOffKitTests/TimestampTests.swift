import Foundation
import Testing

@testable import WhenIOffKit

@Suite("Timestamp")
struct TimestampTests {
    /// 실제 백엔드 응답에서 본 소수점 자릿수 전부 (#34).
    @Test(
        "fractional seconds of every precision the backend emits",
        arguments: [
            ("2026-09-27T22:38:40Z", 0.0),
            ("2026-09-27T22:38:40.5Z", 0.5),
            ("2026-09-27T22:38:40.123Z", 0.123),
            ("2026-09-27T22:38:40.758067Z", 0.758_067),
            ("2026-09-27T22:38:40.662288726Z", 0.662_288_726),
        ]
    )
    func parsesEveryPrecision(text: String, fraction: Double) throws {
        let date = try #require(Timestamp.parse(text))
        #expect(abs(date.timeIntervalSince1970 - (Epoch.stopArrival + fraction)) < 1e-6)
    }

    @Test func offsetsAreNormalizedToUTC() throws {
        let kst = try #require(Timestamp.parse("2026-09-28T07:38:40+09:00"))
        let utc = try #require(Timestamp.parse("2026-09-27T22:38:40Z"))
        #expect(kst == utc)
    }

    @Test(
        "rejects anything that is not an instant",
        arguments: [
            "",
            "2026-09-27T22:38:40",  // 시간대 없음
            "2026-09-27 22:38:40Z",  // 구분자
            "2026-02-30T00:00:00Z",  // 없는 날짜 — Calendar는 3월 2일로 넘긴다
            "2026-13-01T00:00:00Z",
            "2026-09-27T24:00:00Z",
            "2026-09-27T22:38:40.Z",  // 소수점 뒤 숫자 없음
            "2026-09-27T22:38:40.1234567890Z",  // 10자리 — Instant는 9자리까지
            "2026-09-27T22:38:40Zjunk",
            "2026-09-28",  // 날짜만 — tripDate를 시각으로 읽으면 안 된다
        ]
    )
    func rejects(text: String) {
        #expect(Timestamp.parse(text) == nil)
    }

    @Test func formatsUTCWithMilliseconds() {
        #expect(Timestamp.format(Date(timeIntervalSince1970: Epoch.stopArrival)) == "2026-09-27T22:38:40.000Z")
        #expect(Timestamp.format(Date(timeIntervalSince1970: Epoch.stopArrival + 0.5)) == "2026-09-27T22:38:40.500Z")
    }

    @Test func roundingPastTheLastMillisecondCarriesIntoTheSecond() {
        // 소수부만 반올림하면 "40.1000Z" 같은 값이 나온다.
        #expect(Timestamp.format(Date(timeIntervalSince1970: Epoch.stopArrival + 0.9996)) == "2026-09-27T22:38:41.000Z")
    }

    @Test func instantsBeforeTheEpochFloorCorrectly() {
        // 1969-12-31T23:59:59.500Z. 나눗셈이 0 쪽으로 잘리면 "23:59:59"가 아니라 "00:00:00"이 된다.
        #expect(Timestamp.format(Date(timeIntervalSince1970: -0.5)) == "1969-12-31T23:59:59.500Z")
    }

    @Test func formatThenParseRoundTrips() throws {
        let text = "2026-09-27T22:31:05.123Z"
        #expect(Timestamp.format(try #require(Timestamp.parse(text))) == text)
    }
}
