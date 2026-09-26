import Foundation
import Testing

@testable import WhenIOffKit

@Suite("LocalDate")
struct LocalDateTests {
    @Test func parsesAndPrintsTheBackendFormat() throws {
        let date = try #require(LocalDate("2026-09-28"))
        #expect(date == LocalDate(year: 2026, month: 9, day: 28))
        #expect(date.description == "2026-09-28")
    }

    @Test(
        "accepts only yyyy-MM-dd",
        arguments: [
            "2026-9-28",
            "2026-02-30",
            "20260928",
            "2026-09-28T00:00:00Z",
            "２０２６-09-28",  // 전각 숫자는 `isNumber`를 통과하므로 따로 막는다
        ]
    )
    func rejects(text: String) {
        #expect(LocalDate(text) == nil)
    }

    /// `tripDate`는 KST 달력 날짜다. UTC로 날짜를 뽑으면 출근길(KST 오전 7~9시 = UTC 전날 22~24시)이
    /// 전부 **하루 전**으로 기록된다.
    @Test func tripDateFollowsTheKoreanCalendarNotUTC() {
        let leftHome = Date(timeIntervalSince1970: Epoch.leftHome)  // UTC 09-27 22:31 = KST 09-28 07:31
        #expect(LocalDate(leftHome) == LocalDate(year: 2026, month: 9, day: 28))
        #expect(LocalDate(leftHome, in: TimeZone(secondsFromGMT: 0)!) == LocalDate(year: 2026, month: 9, day: 27))
    }

    @Test func theKoreanDayChangesAtFifteenHundredUTC() {
        let lastSecond = Date(timeIntervalSince1970: Epoch.lastSecondOfKstDay)
        #expect(LocalDate(lastSecond) == LocalDate(year: 2026, month: 9, day: 27))
        #expect(LocalDate(lastSecond.addingTimeInterval(1)) == LocalDate(year: 2026, month: 9, day: 28))
    }

    @Test func ordersChronologically() throws {
        let a = try #require(LocalDate("2026-09-28"))
        let b = try #require(LocalDate("2026-10-01"))
        #expect(a < b)
    }

    @Test func encodesAsAPlainDateString() throws {
        let date = try #require(LocalDate("2026-09-28"))
        let json = String(data: try WhenIOffJSON.encoder().encode([date]), encoding: .utf8)
        #expect(json == #"["2026-09-28"]"#)
    }
}
