import Foundation
import WhenIOffKit

/// 알림 계산에 필요한 추천 값 (`GET /commute-routes/{id}/recommendation/latest`).
public struct DepartureAdvice: Sendable, Equatable {
    public var routeId: Int64
    public var recommendedLeaveHomeAt: Date
    public var targetArrivalAt: Date
    public var catchProbability: Double
    public var modelVersion: String

    public init(
        routeId: Int64,
        recommendedLeaveHomeAt: Date,
        targetArrivalAt: Date,
        catchProbability: Double,
        modelVersion: String
    ) {
        self.routeId = routeId
        self.recommendedLeaveHomeAt = recommendedLeaveHomeAt
        self.targetArrivalAt = targetArrivalAt
        self.catchProbability = catchProbability
        self.modelVersion = modelVersion
    }

    public init(routeId: Int64, _ recommendation: DepartureRecommendation) {
        self.init(
            routeId: routeId,
            recommendedLeaveHomeAt: recommendation.recommendedLeaveHomeAt,
            targetArrivalAt: recommendation.targetArrivalAt,
            catchProbability: recommendation.catchProbability,
            modelVersion: recommendation.modelVersion
        )
    }
}

/// 콜드스타트 판정 (#8: 구간별 샘플 5 미만이면 "아직 데이터가 적어 보수적으로 추천").
///
/// 추천 응답에는 샘플 수가 없다. 그래서 앱이 이미 가진 이력(`GET /commute-trips?routeId=`)에서 구간별로 **출발 시각이
/// 기록된 시도 수**를 세고, `model_version`이 v1(콜드스타트 기본값만 쓰는 모델)이면 무조건 콜드스타트로 본다.
/// 기준 5는 analytics `MIN_CALIBRATION_SAMPLES`와 같다.
public enum ColdStart {
    public static let minSamplesPerLeg = 5

    public static func samplesByLeg(_ trips: [CommuteTrip]) -> [Int64: Int] {
        var counts: [Int64: Int] = [:]
        for attempt in trips.flatMap(\.boardingAttempts) where attempt.vehicleActualDepartureAt != nil {
            counts[attempt.routeLegId, default: 0] += 1
        }
        return counts
    }

    public static func isColdStart(modelVersion: String, transitLegIds: [Int64], samplesByLeg: [Int64: Int]) -> Bool {
        if modelVersion == "v1" { return true }
        return transitLegIds.contains { (samplesByLeg[$0] ?? 0) < minSamplesPerLeg }
    }
}

/// 예약할 로컬 알림 (UserNotifications에 그대로 옮긴다).
public struct LocalAlert: Codable, Sendable, Equatable {
    public var identifier: String
    public var fireAt: Date
    public var title: String
    public var body: String

    public init(identifier: String, fireAt: Date, title: String, body: String) {
        self.identifier = identifier
        self.fireAt = fireAt
        self.title = title
        self.body = body
    }
}

/// 할 일. 둘 다 비면 그대로 둔다.
public struct DepartureAlertPlan: Sendable, Equatable {
    public var cancel: [String]
    public var schedule: LocalAlert?

    public init(cancel: [String] = [], schedule: LocalAlert? = nil) {
        self.cancel = cancel
        self.schedule = schedule
    }

    public var isEmpty: Bool { cancel.isEmpty && schedule == nil }
}

/// 출발 알림 예약 계산 (ADR 0003 §8, #8).
///
/// `recommended_leave_home_at − 5분`에 "지금 나가세요 (성공 확률 P%)". 추천이 갱신될 때마다 다시 계산해
/// 바뀐 경우에만 다시 예약한다. 알림 식별자는 (경로, 목표 날짜 KST)라 하루에 한 번만 울린다.
public struct DepartureAlertPlanner: Sendable {
    /// 출발 시각보다 이만큼 먼저 알린다.
    public var leadTime: TimeInterval
    /// 예약된 알림과 이 이내로 차이 나면 다시 예약하지 않는다 (추천 재계산의 작은 흔들림).
    public var rescheduleThreshold: TimeInterval
    /// 목표 도착 이만큼 전부터 추천을 다시 조회한다.
    public var refreshWindow: TimeInterval
    /// 조회 창 안에서의 조회 간격 (BGAppRefreshTask는 이보다 늦게 깨울 수 있다).
    public var refreshInterval: TimeInterval

    public init(
        leadTime: TimeInterval = 5 * 60,
        rescheduleThreshold: TimeInterval = 30,
        refreshWindow: TimeInterval = 120 * 60,
        refreshInterval: TimeInterval = 15 * 60
    ) {
        self.leadTime = leadTime
        self.rescheduleThreshold = rescheduleThreshold
        self.refreshWindow = refreshWindow
        self.refreshInterval = refreshInterval
    }

    public static func identifier(routeId: Int64, targetArrivalAt: Date) -> String {
        "wio.departure.\(routeId).\(LocalDate(targetArrivalAt))"
    }

    /// - Parameters:
    ///   - advice: 최신 추천. 없으면(404) 예약된 알림을 지운다
    ///   - pending: 이 경로로 지금 예약돼 있는 알림
    ///   - delivered: 이미 울린 알림 식별자 — 같은 날 두 번 울리지 않는다
    ///   - alreadyLeft: 오늘 이 경로의 trip이 이미 시작됐다 (이미 나섰으면 알릴 필요가 없다)
    ///   - isColdStart: ``ColdStart/isColdStart(modelVersion:transitLegIds:samplesByLeg:)``
    public func plan(
        advice: DepartureAdvice?,
        pending: LocalAlert?,
        delivered: Set<String>,
        alreadyLeft: Bool,
        isColdStart: Bool,
        now: Date
    ) -> DepartureAlertPlan {
        let desired = advice.flatMap {
            desiredAlert($0, delivered: delivered, alreadyLeft: alreadyLeft, isColdStart: isColdStart, now: now)
        }
        guard let desired else {
            return DepartureAlertPlan(cancel: pending.map { [$0.identifier] } ?? [])
        }
        if let pending, pending.identifier == desired.identifier, pending.title == desired.title,
            pending.body == desired.body, abs(pending.fireAt.timeIntervalSince(desired.fireAt)) < rescheduleThreshold
        {
            return DepartureAlertPlan()
        }
        let stale = pending.flatMap { $0.identifier == desired.identifier ? nil : $0.identifier }
        return DepartureAlertPlan(cancel: stale.map { [$0] } ?? [], schedule: desired)
    }

    private func desiredAlert(
        _ advice: DepartureAdvice,
        delivered: Set<String>,
        alreadyLeft: Bool,
        isColdStart: Bool,
        now: Date
    ) -> LocalAlert? {
        let identifier = Self.identifier(routeId: advice.routeId, targetArrivalAt: advice.targetArrivalAt)
        // 출발 시각이 지났으면 늦었다 — 알림은 도움이 안 된다. 5분 창 안에 들어온 추천은 바로 알린다.
        guard !alreadyLeft, !delivered.contains(identifier), now < advice.recommendedLeaveHomeAt else { return nil }
        let fireAt = max(advice.recommendedLeaveHomeAt.addingTimeInterval(-leadTime), now)
        return LocalAlert(
            identifier: identifier,
            fireAt: fireAt,
            title: "지금 나가세요 (성공 확률 \(Self.percent(advice.catchProbability))%)",
            body: Self.body(advice, isColdStart: isColdStart)
        )
    }

    /// 버림으로 표시한다 — 0.999를 "100%"로 올려 확실하다고 말하지 않는다.
    static func percent(_ probability: Double) -> Int {
        Int((min(max(probability, 0), 1) * 100).rounded(.down))
    }

    static func body(_ advice: DepartureAdvice, isColdStart: Bool) -> String {
        let main = "\(kstClock(advice.recommendedLeaveHomeAt)) 출발 권장 · \(kstClock(advice.targetArrivalAt)) 도착 목표"
        return isColdStart ? main + "\n아직 데이터가 적어 보수적으로 추천했어요" : main
    }

    static func kstClock(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .seoul
        let c = calendar.dateComponents([.hour, .minute], from: date)
        func pad(_ value: Int?) -> String { (value ?? 0) < 10 ? "0\(value ?? 0)" : "\(value ?? 0)" }
        return "\(pad(c.hour)):\(pad(c.minute))"
    }

    /// 다음에 추천을 다시 조회할 시각 (BGAppRefreshTask의 `earliestBeginDate`). 목표 120분 전부터 15분마다,
    /// 출발 시각이 지나면 `nil`(다음 조회는 앱을 열 때).
    public func nextRefreshAt(advice: DepartureAdvice?, now: Date) -> Date? {
        guard let advice, now < advice.recommendedLeaveHomeAt else { return nil }
        let windowStart = advice.targetArrivalAt.addingTimeInterval(-refreshWindow)
        if now < windowStart { return windowStart }
        return min(now.addingTimeInterval(refreshInterval), advice.recommendedLeaveHomeAt)
    }
}
