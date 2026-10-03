import SwiftUI
import WhenIOffKit
import WhenIOffRecorder

/// 경로와 기록 상태: 진행 중 trip, 감시 중인 지점, 보내지 않은 기록, 확인할 것.
struct StatusView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let _ = model.revision
        List {
            if let session = model.session {
                tripSection(session)
                routeSection(session)
                outboxSection(session)
                reviewSection(session)
            }
        }
        .navigationTitle("기록")
        .refreshable { await model.refresh() }
    }

    // MARK: 진행 중

    @ViewBuilder private func tripSection(_ session: RecordingSession) -> some View {
        Section("진행 중") {
            if let trip = session.state.trip {
                LabeledContent(
                    routeName(trip.route.id, session),
                    value: "\(trip.route.direction.text) · \(KST.clock(trip.leftHomeAt)) 출발")
                ForEach(trip.legs.indices, id: \.self) { index in
                    let leg = trip.route.transitLegs[index]
                    let progress = trip.legs[index]
                    LabeledContent(
                        "\(leg.lineName) \(leg.board.name) → \(leg.alight.name)",
                        value: progress.attemptSeq > 1
                            ? "\(progress.phase.text) (\(progress.attemptSeq)번째)" : progress.phase.text)
                }
                if trip.legs.contains(where: { $0.phase == .waiting }) {
                    // 알림을 놓쳤을 때 앱에서 누르는 버튼. 지금 기다리는 시도에 적용된다 (legId·seq 없이).
                    HStack {
                        Button("탔음") {
                            session.handle(.userCaught(legId: nil, attemptSeq: nil, at: Date(), departedAt: nil))
                        }
                        .buttonStyle(.borderedProminent)
                        Spacer()
                        Button("놓쳤음") {
                            session.handle(
                                .userMissed(legId: nil, attemptSeq: nil, at: Date(), departedAt: nil, notes: nil))
                        }
                        .buttonStyle(.bordered)
                    }
                }
                Button("기록 취소", role: .destructive) {
                    session.handle(.userCancelledTrip(nil, at: Date()))
                }
            } else {
                Text("기록 중인 이동이 없어요. 등록한 출발지를 나서면 자동으로 시작해요.")
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: 경로

    @ViewBuilder private func routeSection(_ session: RecordingSession) -> some View {
        Section {
            if session.snapshot.routes.isEmpty {
                Text(model.hasToken ? "활성 경로가 없어요. 데스크탑에서 경로를 등록해 주세요." : "설정에서 API 토큰을 넣어 주세요.")
                    .foregroundStyle(.secondary)
            }
            ForEach(session.snapshot.routes, id: \.id) { route in
                VStack(alignment: .leading) {
                    Text(routeName(route.id, session))
                    Text("\(route.direction.text) · 대중교통 \(route.transitLegs.count)구간")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            LabeledContent("감시 중인 지점", value: "\(session.snapshot.plan.regions.count)곳")
            if !session.snapshot.plan.dropped.isEmpty {
                Text(
                    "iOS 한도(20곳) 때문에 정류장 \(session.snapshot.plan.dropped.count)곳은 자동 기록되지 않아요. 그 구간은 앱의 탔음/놓쳤음과 다음 지점 도착으로 기록돼요."
                )
                .font(.caption)
                .foregroundStyle(.orange)
            }
        } header: {
            Text("경로")
        } footer: {
            if let error = model.routeError {
                Text("경로를 받지 못했어요 (마지막으로 받은 경로로 기록해요): \(error)")
            } else if let fetched = session.snapshot.routesFetchedAt {
                Text("\(KST.dayAndClock(fetched))에 받음")
            }
        }
    }

    // MARK: 전송

    @ViewBuilder private func outboxSection(_ session: RecordingSession) -> some View {
        let summary = session.outbox.summary
        Section("전송") {
            LabeledContent("보내지 않은 기록", value: "\(summary.pendingCount)건")
            if summary.paused {
                Text("서버가 토큰을 거부해 전송을 멈췄어요. 설정에서 토큰을 고치면 다시 보내요.")
                    .foregroundStyle(.red)
            } else if let retry = summary.nextRetryAt {
                LabeledContent("다음 재시도", value: KST.clock(retry))
            }
            if model.client == nil {
                Text("서버 주소나 토큰이 없어 쌓아만 두고 있어요.")
                    .foregroundStyle(.secondary)
            }
            if let error = summary.storageError ?? session.storageError {
                Text("저장 실패: \(error)").foregroundStyle(.red)
            }
        }
    }

    // MARK: 확인할 것

    @ViewBuilder private func reviewSection(_ session: RecordingSession) -> some View {
        let count =
            session.snapshot.reviews.count + session.outbox.summary.deadLetters.count + model.unreadableFiles.count
        if count > 0 {
            Section {
                NavigationLink("확인할 기록 \(count)건") { ReviewListView() }
            }
        }
    }

    private func routeName(_ id: Int64, _ session: RecordingSession) -> String {
        session.snapshot.routeNames[id] ?? "경로 \(id)"
    }
}

/// 자동 기록이 확신하지 못한 것과 서버가 거부한 기록. 고칠 것은 이력 탭의 trip 상세에서 고친다.
struct ReviewListView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let _ = model.revision
        List {
            if let session = model.session {
                if !model.unreadableFiles.isEmpty {
                    Section("읽지 못한 저장 파일") {
                        ForEach(model.unreadableFiles, id: \.self) { Text($0).font(.caption.monospaced()) }
                        Text("앱 업데이트로 형식이 바뀌어 옆으로 옮겨 두었어요. 지우지 않았어요.")
                            .foregroundStyle(.secondary)
                    }
                }
                Section("자동 기록 확인") {
                    ForEach(Array(session.snapshot.reviews.enumerated()), id: \.offset) { _, note in
                        VStack(alignment: .leading) {
                            Text(note.kind.text)
                            Text(
                                "\(session.snapshot.routeNames[note.tripKey.routeId] ?? "경로") · \(KST.dayAndClock(note.at))"
                            )
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                    }
                }
                Section("서버가 거부한 기록") {
                    ForEach(Array(session.outbox.summary.deadLetters.enumerated()), id: \.offset) { _, letter in
                        VStack(alignment: .leading) {
                            Text(letter.text)
                            Text(KST.dayAndClock(letter.at)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    Button("모두 확인했어요") { session.clearReviews() }
                } footer: {
                    Text("시각을 고치려면 이력 탭에서 그날 기록을 열어 주세요.")
                }
            }
        }
        .navigationTitle("확인할 기록")
    }
}
