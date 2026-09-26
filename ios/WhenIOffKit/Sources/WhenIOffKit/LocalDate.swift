import Foundation

extension TimeZone {
    /// 한국 표준시. 출퇴근 날짜(`tripDate`)와 시간표의 `day_type`은 모두 KST 달력으로 정해진다.
    public static let seoul = TimeZone(identifier: "Asia/Seoul")!
}

/// 시각이 아닌 **달력 날짜** (`"2026-09-28"`). 백엔드의 `LocalDate`에 대응한다.
///
/// `tripDate`를 `Date`로 두면 시각 파서가 `"2026-09-28"`을 못 읽고, 읽더라도 어느 시간대의 자정인지가
/// 모호해진다. 날짜는 날짜로만 다룬다.
public struct LocalDate: Hashable, Comparable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    /// 존재하지 않는 날짜(2월 30일 등)면 `nil`.
    public init?(year: Int, month: Int, day: Int) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard (1...12).contains(month), (1...31).contains(day),
            let date = calendar.date(from: DateComponents(year: year, month: month, day: day))
        else { return nil }
        let check = calendar.dateComponents([.year, .month, .day], from: date)
        guard check.year == year, check.month == month, check.day == day else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    /// 정확히 `yyyy-MM-dd`만 받는다 (`2026-9-1`처럼 자릿수가 다르면 `nil`).
    public init?(_ string: String) {
        let parts = string.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
            parts.allSatisfy({ $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
            let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2])
        else { return nil }
        self.init(year: year, month: month, day: day)
    }

    /// `date`가 `timeZone`에서 몇 월 며칠인가. 기본은 KST — UTC 22:31은 KST로 다음날 07:31이다.
    public init(_ date: Date, in timeZone: TimeZone = .seoul) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        year = c.year!
        month = c.month!
        day = c.day!
    }

    /// 지금 KST 날짜. 출근 기록의 `tripDate`로 쓴다.
    public static func today(now: Date = Date(), in timeZone: TimeZone = .seoul) -> LocalDate {
        LocalDate(now, in: timeZone)
    }

    public var description: String {
        "\(Timestamp.pad(year, 4))-\(Timestamp.pad(month, 2))-\(Timestamp.pad(day, 2))"
    }

    public static func < (lhs: LocalDate, rhs: LocalDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }
}

extension LocalDate: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let text = try container.decode(String.self)
        guard let date = LocalDate(text) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "not a yyyy-MM-dd date: \(text)")
        }
        self = date
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(description)
    }
}
