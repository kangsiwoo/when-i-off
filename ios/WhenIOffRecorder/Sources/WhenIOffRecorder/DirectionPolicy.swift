import Foundation
import WhenIOffKit

/// 출근/퇴근 판별 (ADR 0003 §3).
///
/// **이탈한 geofence가 먼저, 시각은 그 다음이다.** 집을 나서면 집이 출발지인 경로(보통 출근), 회사를 나서면
/// 회사가 출발지인 경로(보통 퇴근)다. 시각은 (1) 같은 장소가 여러 경로의 출발지일 때 고르는 기준,
/// (2) 평소와 다른 시각의 출발을 사용자에게 확인받을지, (3) 20개 한도에서 어느 경로의 정류장을 먼저 걸지에만 쓴다.
public struct DirectionPolicy: Codable, Sendable, Equatable {
    /// KST 시(hour) 구간. 이 안이면 출근, 밖이면 퇴근이 "평소"다. 기본 04:00–14:00.
    public var toWorkHours: Range<Int>

    public init(toWorkHours: Range<Int> = 4..<14) {
        self.toWorkHours = toWorkHours
    }

    public func usualDirection(at date: Date) -> CommuteDirection {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .seoul
        return toWorkHours.contains(calendar.component(.hour, from: date)) ? .toWork : .toHome
    }

    /// 경로들을 `date`의 평소 방향이 먼저 오도록, 같은 방향 안에서는 id 순으로 정렬한다 (결정적).
    func prioritized(_ routes: [RecorderRoute], at date: Date) -> [RecorderRoute] {
        let usual = usualDirection(at: date)
        return routes.sorted { a, b in
            let aUsual = a.direction == usual
            let bUsual = b.direction == usual
            if aUsual != bUsual { return aUsual }
            return a.id < b.id
        }
    }

    /// 이탈한 지역이 출발지인 경로 중 하나를 고른다. 평소 방향이 아니면 `outsideUsualHours`.
    func routeToStart(exiting roles: [RegionRole], routes: [RecorderRoute], at date: Date) -> (
        route: RecorderRoute, outsideUsualHours: Bool
    )? {
        let origins = Set(
            roles.compactMap { role -> Int64? in
                if case .origin(let routeId) = role { return routeId }
                return nil
            })
        let candidates = prioritized(routes.filter { origins.contains($0.id) }, at: date)
        guard let chosen = candidates.first else { return nil }
        return (chosen, chosen.direction != usualDirection(at: date))
    }
}
