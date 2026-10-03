import Foundation
import WhenIOffKit

/// WGS84 좌표.
public struct Coordinate: Codable, Sendable, Hashable {
    public var lat: Double
    public var lng: Double

    public init(lat: Double, lng: Double) {
        self.lat = lat
        self.lng = lng
    }

    /// 대권 거리(m). geofence 병합 판단에만 쓰므로 하버사인으로 충분하다.
    public func distance(to other: Coordinate) -> Double {
        let earthRadius = 6_371_000.0
        let toRadians = Double.pi / 180
        let dLat = (other.lat - lat) * toRadians
        let dLng = (other.lng - lng) * toRadians
        let a =
            sin(dLat / 2) * sin(dLat / 2)
            + cos(lat * toRadians) * cos(other.lat * toRadians) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * earthRadius * atan2(a.squareRoot(), (1 - a).squareRoot())
    }
}

/// 승차·하차 정류장.
public struct StopPoint: Codable, Sendable, Equatable {
    public var id: Int64
    public var name: String
    public var coordinate: Coordinate

    public init(id: Int64, name: String, coordinate: Coordinate) {
        self.id = id
        self.name = name
        self.coordinate = coordinate
    }
}

/// 기록 대상인 TRANSIT 구간 하나. 탑승 시도(boarding attempt)는 이 구간 단위로 쌓인다.
public struct RecorderTransitLeg: Codable, Sendable, Equatable {
    public var legId: Int64
    public var mode: TransitMode
    /// 알림에 띄울 노선 이름 (예: "GTX-A (수서~동탄)").
    public var lineName: String
    public var board: StopPoint
    public var alight: StopPoint

    public init(legId: Int64, mode: TransitMode, lineName: String, board: StopPoint, alight: StopPoint) {
        self.legId = legId
        self.mode = mode
        self.lineName = lineName
        self.board = board
        self.alight = alight
    }
}

/// 기록 코어가 쓰는 경로의 요약. 서버 응답(`CommuteRouteDetail`)에서 만든다.
///
/// 상태기계가 진행 중인 trip과 함께 이 값을 **복사해 보관한다**. 기록 도중 경로가 바뀌어도 그 trip은
/// 시작할 때의 구간으로 끝까지 기록된다.
public struct RecorderRoute: Codable, Sendable, Equatable {
    public var id: Int64
    public var direction: CommuteDirection
    public var origin: Coordinate
    public var destination: Coordinate
    /// `seqOrder` 순.
    public var transitLegs: [RecorderTransitLeg]

    public init(
        id: Int64,
        direction: CommuteDirection,
        origin: Coordinate,
        destination: Coordinate,
        transitLegs: [RecorderTransitLeg]
    ) {
        self.id = id
        self.direction = direction
        self.origin = origin
        self.destination = destination
        self.transitLegs = transitLegs
    }

    /// 정류장 객체(#36)가 없는 TRANSIT 구간은 geofence를 걸 수 없어 기록 대상에서 빠진다.
    /// 지금 백엔드는 TRANSIT 구간에 항상 객체를 준다.
    public init(_ detail: CommuteRouteDetail) {
        self.init(
            id: detail.route.id,
            direction: detail.route.direction,
            origin: Coordinate(lat: detail.route.originLat, lng: detail.route.originLng),
            destination: Coordinate(lat: detail.route.destinationLat, lng: detail.route.destinationLng),
            transitLegs: detail.transitLegs.compactMap { leg in
                guard let board = leg.boardStop, let alight = leg.alightStop else { return nil }
                return RecorderTransitLeg(
                    legId: leg.id,
                    mode: leg.transitLine?.mode ?? board.mode,
                    lineName: leg.transitLine?.name ?? "",
                    board: StopPoint(
                        id: board.id, name: board.name, coordinate: Coordinate(lat: board.lat, lng: board.lng)),
                    alight: StopPoint(
                        id: alight.id, name: alight.name, coordinate: Coordinate(lat: alight.lat, lng: alight.lng))
                )
            }
        )
    }

    func legIndex(of legId: Int64) -> Int? {
        transitLegs.firstIndex { $0.legId == legId }
    }
}
