import Foundation
import WhenIOffKit

extension APIClient {
    /// outbox 요청 하나를 보내고 결과를 ``OutboxOutcome``으로 돌려준다. 던지지 않는다 — 실패도 결과다.
    public func send(_ request: OutboxRequest) async -> OutboxOutcome {
        do {
            switch request {
            case .createTrip(let body):
                return .tripCreated(id: try await createTrip(body).id)
            case .updateTrip(let tripId, let body):
                _ = try await updateTrip(id: tripId, body)
            case .upsertAttempt(let tripId, let body):
                _ = try await upsertBoardingAttempt(tripId: tripId, body)
            case .uploadGps(let body):
                _ = try await uploadGpsTraces(body)
            case .deleteTrip(let tripId):
                try await deleteTrip(id: tripId)
            }
            return .delivered
        } catch {
            return .failed(error)
        }
    }
}
