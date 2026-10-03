import Foundation
import WhenIOffKit

/// geofence 하나가 기록에서 맡는 역할. 가까운 지점은 한 지역으로 합쳐지므로 지역 하나가 여러 역할을 가질 수 있다
/// (예: 출근 경로의 출발지 = 퇴근 경로의 도착지 = 집, 환승역의 하차 + 다음 구간 승차).
public enum RegionRole: Codable, Sendable, Hashable {
    case origin(routeId: Int64)
    case destination(routeId: Int64)
    case boardStop(routeId: Int64, legId: Int64)
    case alightStop(routeId: Int64, legId: Int64)

    public var routeId: Int64 {
        switch self {
        case .origin(let routeId), .destination(let routeId): return routeId
        case .boardStop(let routeId, _), .alightStop(let routeId, _): return routeId
        }
    }

    /// 이 앱이 거는 지역 식별자의 접두사. 다른 접두사의 지역은 동기화에서 건드리지 않는다.
    public static let identifierPrefix = "wio."

    /// 앱을 다시 설치하거나 계획을 다시 세워도 같은 지점은 같은 식별자를 갖는다 — 어댑터가 등록된 지역과
    /// 계획을 식별자로 비교해 바뀐 것만 다시 건다 (``GeofencePlan/sync(registered:tolerance:)``).
    var identifier: String {
        switch self {
        case .origin(let routeId): return "wio.o.\(routeId)"
        case .destination(let routeId): return "wio.d.\(routeId)"
        case .boardStop(let routeId, let legId): return "wio.b.\(routeId).\(legId)"
        case .alightStop(let routeId, let legId): return "wio.a.\(routeId).\(legId)"
        }
    }
}

/// 등록할 원형 지역 하나.
public struct PlannedRegion: Codable, Sendable, Equatable {
    public var identifier: String
    public var center: Coordinate
    public var radius: Double
    /// 우선순위 순. 첫 역할이 식별자를 정한다.
    public var roles: [RegionRole]
}

public struct GeofencePlan: Codable, Sendable, Equatable {
    /// 우선순위 순, 최대 ``GeofencePlanner/maxRegions``개.
    public var regions: [PlannedRegion]
    /// 한도 때문에 걸지 못한 역할 (우선순위 순). 화면에 "정류장 n곳은 자동 기록되지 않음"으로 알린다.
    public var dropped: [RegionRole]

    public init(regions: [PlannedRegion] = [], dropped: [RegionRole] = []) {
        self.regions = regions
        self.dropped = dropped
    }

    public func roles(for identifier: String) -> [RegionRole] {
        regions.first { $0.identifier == identifier }?.roles ?? []
    }
}

/// 반경 기본값 (#4). 지하철·GTX 역은 지하에서 GPS가 끊겨 진입이 늦게 잡히므로 넓게 잡는다.
public struct RadiusPolicy: Codable, Sendable, Equatable {
    public var place: Double
    public var busStop: Double
    public var railStation: Double

    public init(place: Double = 100, busStop: Double = 60, railStation: Double = 120) {
        self.place = place
        self.busStop = busStop
        self.railStation = railStation
    }

    public func stopRadius(for mode: TransitMode) -> Double {
        switch mode {
        case .bus: return busStop
        case .subway, .gtx: return railStation
        }
    }
}

/// 활성 경로들의 지점을 iOS geofence 한도(앱당 20개) 안에서 고른다 (ADR 0003 §3).
///
/// 우선순위 (같은 단계 안에서는 경로 우선순위 → 구간 순서):
/// 1. 모든 경로의 **출발지** — trip을 시작하고 방향을 판별하는 유일한 근거다
/// 2. 주 경로(진행 중인 trip의 경로, 없으면 지금 시각의 평소 방향)의 도착지, 구간마다 승차 → 하차 정류장
/// 3. 나머지 경로의 도착지와 정류장
///
/// `mergeDistance` 안의 지점은 한 지역으로 합치고 반경은 큰 쪽을 쓴다. 합치기는 한도 검사보다 먼저 하므로
/// 이미 걸린 지점과 겹치는 역할은 한도에 걸려도 버려지지 않는다.
public struct GeofencePlanner: Sendable {
    public static let maxRegions = 20

    public var radius: RadiusPolicy
    public var direction: DirectionPolicy
    public var mergeDistance: Double
    public var maxRegions: Int

    public init(
        radius: RadiusPolicy = RadiusPolicy(),
        direction: DirectionPolicy = DirectionPolicy(),
        mergeDistance: Double = 30,
        maxRegions: Int = GeofencePlanner.maxRegions
    ) {
        self.radius = radius
        self.direction = direction
        self.mergeDistance = mergeDistance
        self.maxRegions = min(maxRegions, GeofencePlanner.maxRegions)
    }

    /// - Parameter activeRouteId: 진행 중인 trip의 경로. 있으면 시각과 무관하게 주 경로가 된다.
    public func plan(routes: [RecorderRoute], now: Date, activeRouteId: Int64? = nil) -> GeofencePlan {
        var ordered = direction.prioritized(routes, at: now)
        if let activeRouteId, let index = ordered.firstIndex(where: { $0.id == activeRouteId }) {
            ordered.insert(ordered.remove(at: index), at: 0)
        }

        var candidates: [(RegionRole, Coordinate, Double)] = ordered.map {
            (.origin(routeId: $0.id), $0.origin, radius.place)
        }
        for route in ordered {
            candidates.append((.destination(routeId: route.id), route.destination, radius.place))
            for leg in route.transitLegs {
                let stopRadius = radius.stopRadius(for: leg.mode)
                candidates.append((.boardStop(routeId: route.id, legId: leg.legId), leg.board.coordinate, stopRadius))
                candidates.append((.alightStop(routeId: route.id, legId: leg.legId), leg.alight.coordinate, stopRadius))
            }
        }

        var plan = GeofencePlan()
        for (role, center, regionRadius) in candidates {
            if let index = plan.regions.firstIndex(where: { $0.center.distance(to: center) <= mergeDistance }) {
                if !plan.regions[index].roles.contains(role) { plan.regions[index].roles.append(role) }
                plan.regions[index].radius = max(plan.regions[index].radius, regionRadius)
            } else if plan.regions.count < maxRegions {
                plan.regions.append(
                    PlannedRegion(identifier: role.identifier, center: center, radius: regionRadius, roles: [role]))
            } else {
                plan.dropped.append(role)
            }
        }
        return plan
    }
}

// MARK: - 등록 동기화

/// 지금 OS에 등록돼 있는 지역. 어댑터가 `CLCircularRegion`에서 옮긴다.
public struct MonitoredRegion: Sendable, Equatable {
    public var identifier: String
    public var center: Coordinate
    public var radius: Double

    public init(identifier: String, center: Coordinate, radius: Double) {
        self.identifier = identifier
        self.center = center
        self.radius = radius
    }
}

/// 등록된 지역을 계획에 맞추려면 할 일. 한도(20개) 때문에 `stop`을 먼저 실행한다.
public struct GeofenceSync: Sendable, Equatable {
    /// 그만 감시할 식별자 (정렬됨).
    public var stop: [String]
    /// 새로 (또는 다시) 걸 지역 (계획 순).
    public var start: [PlannedRegion]

    public var isEmpty: Bool { stop.isEmpty && start.isEmpty }
}

extension GeofencePlan {
    /// 식별자는 역할에서 만들어지므로 다시 계획해도 같은 지점은 같은 식별자다. 바뀐 것만 다시 건다 —
    /// 그대로 둔 지역은 OS가 이미 아는 안/밖 상태를 잃지 않는다.
    ///
    /// 이 앱의 식별자(``RegionRole/identifierPrefix``)가 아닌 지역은 건드리지 않는다. 같은 식별자라도 중심이나
    /// 반경이 `tolerance`(m)보다 많이 바뀌었으면(경로의 정류장을 고쳤다) 내렸다가 다시 건다.
    public func sync(registered: [MonitoredRegion], tolerance: Double = 1) -> GeofenceSync {
        let ours = registered.filter { $0.identifier.hasPrefix(RegionRole.identifierPrefix) }
        let wanted = Dictionary(regions.map { ($0.identifier, $0) }, uniquingKeysWith: { first, _ in first })
        var keep = Set<String>()
        var stop: [String] = []
        for region in ours {
            if let planned = wanted[region.identifier], !keep.contains(region.identifier),
                planned.center.distance(to: region.center) <= tolerance,
                abs(planned.radius - region.radius) <= tolerance
            {
                keep.insert(region.identifier)
            } else {
                stop.append(region.identifier)
            }
        }
        return GeofenceSync(
            stop: Array(Set(stop)).sorted(),
            start: regions.filter { !keep.contains($0.identifier) }
        )
    }
}
