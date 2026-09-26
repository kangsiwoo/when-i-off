import Foundation

/// 백엔드 `Instant` ↔ `Date` 변환.
///
/// 백엔드는 `Instant.toString()` 그대로 내보내서 **소수점 초 자릿수가 값마다 다르다.** 실제 응답에서
/// 없음(`22:38:40Z`), 3자리(`.123Z`), 6자리(`.758067Z`, DB `timestamptz`), 9자리(`.662288726Z`)를
/// 모두 봤다 (#34).
///
/// Foundation의 `.iso8601` 전략에 맡기지 않고 직접 파싱하는 이유:
/// - **동작이 Foundation 구현마다 다르다.** Swift 6.4의 `swift-foundation`(Linux)은 위 자릿수를 모두 읽지만,
///   예전 `ISO8601DateFormatter` 기반 구현은 소수점 초를 거부했다. 앱 최소 버전(iOS 17)에서 어느 쪽인지는
///   이 패키지의 CI(Linux)로 확인할 수 없으므로, 모든 플랫폼에서 같은 동작을 보장하는 쪽을 택한다
/// - **존재하지 않는 날짜를 거부한다.** `swift-foundation`의 `.iso8601`은 `2026-02-30`을 에러 없이
///   3월 2일로 넘긴다. 출발 시각을 다루는 앱에서 틀린 날짜를 조용히 받아들이는 것보다 실패가 낫다
public enum Timestamp {
    private static let utc = TimeZone(secondsFromGMT: 0)!

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar
    }

    /// `yyyy-MM-ddTHH:mm:ss[.f{1,9}](Z|±HH:MM)`을 읽는다. 형식이 어긋나거나 존재하지 않는
    /// 날짜(2월 30일 등)면 `nil`.
    public static func parse(_ string: String) -> Date? {
        let bytes = Array(string.utf8)
        guard bytes.count >= 20 else { return nil }

        func digits(_ start: Int, _ length: Int) -> Int? {
            guard start + length <= bytes.count else { return nil }
            var value = 0
            for byte in bytes[start..<(start + length)] {
                guard byte >= UInt8(ascii: "0"), byte <= UInt8(ascii: "9") else { return nil }
                value = value * 10 + Int(byte - UInt8(ascii: "0"))
            }
            return value
        }

        guard let year = digits(0, 4), bytes[4] == UInt8(ascii: "-"),
            let month = digits(5, 2), bytes[7] == UInt8(ascii: "-"),
            let day = digits(8, 2), bytes[10] == UInt8(ascii: "T") || bytes[10] == UInt8(ascii: "t"),
            let hour = digits(11, 2), bytes[13] == UInt8(ascii: ":"),
            let minute = digits(14, 2), bytes[16] == UInt8(ascii: ":"),
            let second = digits(17, 2)
        else { return nil }

        var index = 19
        var fraction = 0.0
        if index < bytes.count, bytes[index] == UInt8(ascii: ".") {
            let start = index + 1
            index = start
            while index < bytes.count, bytes[index] >= UInt8(ascii: "0"), bytes[index] <= UInt8(ascii: "9") {
                index += 1
            }
            let count = index - start
            // Instant는 최대 나노초(9자리)까지 낸다. 그보다 길면 우리가 모르는 형식이다.
            guard (1...9).contains(count), let value = digits(start, count) else { return nil }
            fraction = Double(value) / pow(10, Double(count))
        }

        guard index < bytes.count else { return nil }
        let offsetSeconds: Int
        switch bytes[index] {
        case UInt8(ascii: "Z"), UInt8(ascii: "z"):
            offsetSeconds = 0
            index += 1
        case UInt8(ascii: "+"), UInt8(ascii: "-"):
            guard let offsetHour = digits(index + 1, 2), index + 3 < bytes.count,
                bytes[index + 3] == UInt8(ascii: ":"), let offsetMinute = digits(index + 4, 2),
                offsetHour <= 23, offsetMinute <= 59
            else { return nil }
            let sign = bytes[index] == UInt8(ascii: "-") ? -1 : 1
            offsetSeconds = sign * (offsetHour * 3600 + offsetMinute * 60)
            index += 6
        default:
            return nil
        }
        guard index == bytes.count else { return nil }

        guard (1...12).contains(month), (1...31).contains(day), hour <= 23, minute <= 59, second <= 59
        else { return nil }

        let components = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        let calendar = calendar
        guard let base = calendar.date(from: components) else { return nil }
        // Calendar는 2월 30일을 3월 2일로 넘겨버린다. 되돌려 읽어 같은 날인지 확인해 존재하지 않는 날짜를 거른다.
        let check = calendar.dateComponents([.year, .month, .day], from: base)
        guard check.year == year, check.month == month, check.day == day else { return nil }

        return base.addingTimeInterval(fraction - Double(offsetSeconds))
    }

    /// `yyyy-MM-ddTHH:mm:ss.SSSZ` (UTC, 밀리초). 백엔드가 그대로 `Instant`로 읽는다.
    public static func format(_ date: Date) -> String {
        // 밀리초로 반올림한 뒤 초와 나머지로 쪼갠다. 반올림이 1000ms로 넘어가면 초가 올라가야 하므로
        // 소수부를 따로 반올림하지 않는다.
        let totalMillis = Int64((date.timeIntervalSince1970 * 1000).rounded())
        let seconds = floorDivide(totalMillis, 1000)
        let millis = totalMillis - seconds * 1000
        let whole = Date(timeIntervalSince1970: TimeInterval(seconds))
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: whole)
        return "\(pad(c.year!, 4))-\(pad(c.month!, 2))-\(pad(c.day!, 2))T"
            + "\(pad(c.hour!, 2)):\(pad(c.minute!, 2)):\(pad(c.second!, 2)).\(pad(Int(millis), 3))Z"
    }

    /// `String(format: "%02d")`는 `Int` 인자의 해석이 플랫폼마다 달라 직접 채운다.
    static func pad(_ value: Int, _ width: Int) -> String {
        let text = String(value)
        return text.count >= width ? text : String(repeating: "0", count: width - text.count) + text
    }

    private static func floorDivide(_ a: Int64, _ b: Int64) -> Int64 {
        let quotient = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? quotient - 1 : quotient
    }
}
