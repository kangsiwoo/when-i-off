import SwiftUI
import WhenIOffKit
import WhenIOffRecorder

/// 서버에 올라간 최근 기록 (최근 30일).
struct TripListView: View {
    @EnvironmentObject private var model: AppModel
    @State private var trips: [CommuteTrip] = []
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                Text(error).foregroundStyle(.red)
            }
            ForEach(trips) { trip in
                NavigationLink {
                    TripDetailView(trip: trip, onChange: { updated in replace(updated) })
                } label: {
                    VStack(alignment: .leading) {
                        Text(model.session?.snapshot.routeNames[trip.routeId] ?? "경로 \(trip.routeId)")
                        Text(summary(trip)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("이력")
        .task { await load() }
        .refreshable { await load() }
    }

    private func summary(_ trip: CommuteTrip) -> String {
        let left = trip.leftHomeAt.map(KST.clock) ?? "?"
        let arrived = trip.arrivedDestinationAt.map(KST.clock) ?? "?"
        return "\(trip.tripDate) · \(left) → \(arrived) · 탑승 시도 \(trip.boardingAttempts.count)건"
    }

    private func load() async {
        guard let client = model.client else {
            error = "설정에서 API 토큰을 넣어 주세요."
            return
        }
        do {
            let from = LocalDate(Date().addingTimeInterval(-30 * 24 * 3600))
            trips = try await client.trips(from: from).sorted {
                ($0.leftHomeAt ?? $0.createdAt) > ($1.leftHomeAt ?? $1.createdAt)
            }
            error = nil
        } catch {
            self.error = (error as? APIError)?.message ?? String(describing: error)
        }
    }

    private func replace(_ trip: CommuteTrip) {
        if let index = trips.firstIndex(where: { $0.id == trip.id }) { trips[index] = trip }
    }
}

/// 자동 기록된 시각을 고친다 (#4: 지하 GPS 유실로 늦게 잡힌 `alightedAt` 등).
///
/// 고치기는 바로 서버에 보낸다(PATCH) — 사용자가 화면을 보고 있고 결과(서버의 400 규칙 위반 포함)를 바로 알려야
/// 해서 outbox를 거치지 않는다. 대신 이 trip에 아직 보내지 않은 자동 기록이 있으면 막는다: 뒤에 나가는 upsert가
/// 고친 값을 덮어쓸 수 있다.
struct TripDetailView: View {
    @EnvironmentObject private var model: AppModel
    @State var trip: CommuteTrip
    var onChange: (CommuteTrip) -> Void
    @State private var editing: TimeEdit?
    @State private var error: String?

    var body: some View {
        let _ = model.revision
        let busy = model.session?.outbox.summary.busyTripIds.contains(trip.id) ?? false
        List {
            if busy {
                Text("아직 보내지 않은 자동 기록이 있어요. 전송이 끝나면 고칠 수 있어요.")
                    .foregroundStyle(.orange)
            }
            if let error {
                Text(error).foregroundStyle(.red)
            }
            Section("이동") {
                timeRow("집 나섬", trip.leftHomeAt, busy) { date in
                    try await client().updateTrip(id: trip.id, UpdateCommuteTripRequest(leftHomeAt: date))
                }
                timeRow("도착", trip.arrivedDestinationAt, busy) { date in
                    try await client().updateTrip(id: trip.id, UpdateCommuteTripRequest(arrivedDestinationAt: date))
                }
            }
            ForEach(trip.boardingAttempts) { attempt in
                Section(attemptTitle(attempt)) {
                    attemptRow("정류장 도착", attempt.arrivedAtStopAt, attempt, busy) {
                        UpdateBoardingAttemptRequest(arrivedAtStopAt: $0)
                    }
                    attemptRow("차 출발", attempt.vehicleActualDepartureAt, attempt, busy) {
                        UpdateBoardingAttemptRequest(vehicleActualDepartureAt: $0)
                    }
                    attemptRow("하차", attempt.alightedAt, attempt, busy) {
                        UpdateBoardingAttemptRequest(alightedAt: $0)
                    }
                    if let notes = attempt.notes, !notes.isEmpty {
                        LabeledContent("메모", value: notes)
                    }
                }
            }
        }
        .navigationTitle("\(trip.tripDate)")
        .sheet(item: $editing) { edit in
            TimeEditSheet(edit: edit) { date in
                do {
                    try await edit.save(date)
                    error = nil
                } catch {
                    self.error = (error as? APIError)?.message ?? String(describing: error)
                }
                editing = nil
            }
        }
    }

    private func client() throws -> APIClient {
        guard let client = model.client else { throw APIError.invalidRequest("서버 주소나 토큰이 없어요") }
        return client
    }

    private func timeRow(
        _ title: String, _ value: Date?, _ busy: Bool, save: @escaping (Date) async throws -> CommuteTrip
    ) -> some View {
        Button {
            editing = TimeEdit(title: title, initial: value ?? trip.leftHomeAt ?? Date()) { date in
                let updated = try await save(date)
                trip = updated
                onChange(updated)
            }
        } label: {
            LabeledContent(title, value: value.map(KST.clock) ?? "비어 있음")
        }
        .disabled(busy)
    }

    private func attemptRow(
        _ title: String, _ value: Date?, _ attempt: BoardingAttempt, _ busy: Bool,
        request: @escaping (Date) -> UpdateBoardingAttemptRequest
    ) -> some View {
        Button {
            editing = TimeEdit(title: title, initial: value ?? attempt.arrivedAtStopAt ?? trip.leftHomeAt ?? Date()) {
                date in
                _ = try await client().updateBoardingAttempt(id: attempt.id, request(date))
                try await reload()
            }
        } label: {
            LabeledContent(title, value: value.map(KST.clock) ?? "비어 있음")
        }
        .disabled(busy)
    }

    /// 시도를 고친 뒤 그날 기록을 다시 받는다 (단건 조회 API가 없다).
    private func reload() async throws {
        let client = try client()
        let trips = try await client.trips(routeId: trip.routeId, from: trip.tripDate, to: trip.tripDate)
        if let updated = trips.first(where: { $0.id == trip.id }) {
            trip = updated
            onChange(updated)
        }
    }

    private func attemptTitle(_ attempt: BoardingAttempt) -> String {
        "\(legName(attempt.routeLegId)) · \(attempt.attemptSeq ?? 1)번째 · \(resultText(attempt.result))"
    }

    private func legName(_ legId: Int64) -> String {
        let legs = model.session?.snapshot.routes.flatMap(\.transitLegs) ?? []
        guard let leg = legs.first(where: { $0.legId == legId }) else { return "구간 \(legId)" }
        return leg.lineName.isEmpty ? "\(leg.board.name) → \(leg.alight.name)" : leg.lineName
    }

    private func resultText(_ result: BoardingResult) -> String {
        switch result {
        case .caught: return "탔음"
        case .missed: return "놓쳤음"
        case .unknown: return "모름"
        }
    }
}

struct TimeEdit: Identifiable {
    let id = UUID()
    var title: String
    var initial: Date
    var save: (Date) async throws -> Void
}

struct TimeEditSheet: View {
    let edit: TimeEdit
    var commit: (Date) async -> Void
    @State private var date = Date()
    @State private var saving = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                DatePicker(edit.title, selection: $date, displayedComponents: [.date, .hourAndMinute])
                    .environment(\.timeZone, .seoul)
                Text("서버 규칙: 탑승 시각은 집 나섬과 도착 사이, 하차는 출발보다 늦어야 해요.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .navigationTitle(edit.title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("취소") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        saving = true
                        Task { await commit(date) }
                    }
                    .disabled(saving)
                }
            }
            .onAppear { date = edit.initial }
        }
    }
}
