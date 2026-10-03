import Foundation
import WhenIOffKit
import WhenIOffRecorder

// 화면과 알림에 쓰는 한국어 문구. 시각은 KST로 보여 준다 (저장·전송은 UTC).

enum KST {
    static func clock(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = .seoul
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    static func dayAndClock(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = .seoul
        formatter.dateFormat = "M/d (E) HH:mm"
        return formatter.string(from: date)
    }
}

extension ReviewNote.Kind {
    var text: String {
        switch self {
        case .leftHomeApproximated: return "집 나선 시각을 놓쳐 첫 정류장 도착 시각으로 기록했어요. 실제 출발로 앞당겨 주세요"
        case .boardStopMissed: return "승차 정류장 도착이 잡히지 않았어요 (지하). 도착·출발 시각이 비어 있어요"
        case .caughtInferred: return "탔음/놓쳤음 없이 다음 지점에 도착해 \"탔음\"으로 기록했어요. 출발 시각이 비어 있어요"
        case .alightMissed: return "하차 정류장 도착이 잡히지 않았어요. 하차 시각이 비어 있어요"
        case .legSkipped: return "지점 도착이 하나도 없이 지나간 구간이 있어요"
        case .timeOutOfOrder: return "시각 순서가 맞지 않아 그 값을 보내지 않았어요"
        case .attemptLimitReached: return "한 구간에서 20번 놓쳐 더는 기록하지 않아요"
        case .tripAbandoned: return "집으로 돌아와 머물러 기록을 접었어요"
        case .tripTimedOut: return "도착 없이 4시간이 지나 기록을 접었어요"
        case .unusualDirection: return "평소와 다른 시각에 기록을 시작했어요. 맞지 않으면 취소하세요"
        }
    }
}

extension DeadLetter {
    var text: String {
        switch reason {
        case .rejected(let status, let message):
            return status.map { "서버가 거부했어요 (\($0)): \(message)" } ?? "보내지 못했어요: \(message)"
        case .tripRejected:
            return "trip 생성이 거부돼 함께 보내지 못했어요"
        }
    }
}

extension CommuteDirection {
    var text: String {
        switch self {
        case .toWork: return "출근"
        case .toHome: return "퇴근"
        }
    }
}

extension LegProgress.Phase {
    var text: String {
        switch self {
        case .notStarted: return "아직"
        case .waiting: return "기다리는 중"
        case .riding: return "타고 가는 중"
        case .done: return "끝"
        }
    }
}
