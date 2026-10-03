import Foundation
import WhenIOffRecorder

/// Application Support 아래 JSON 파일 하나 (ADR 0003 §5).
///
/// - 원자적 쓰기: 쓰다가 죽어도 이전 내용이 남는다
/// - 보호 등급 `completeUntilFirstUserAuthentication`: 재부팅 후 한 번 잠금을 풀었으면 기기가 잠겨 있어도 geofence로
///   깨어난 앱이 읽고 쓴다
/// - 형식이 맞지 않는 파일(Kit DTO가 바뀐 앱 업데이트 뒤)은 지우지 않고 옆으로 옮긴다(`<이름>.unreadable-<시각>.json`).
///   화면이 ``AppPaths/unreadableFiles(in:fileManager:)``로 찾아 알린다 — 조용히 버리지 않는다
/// - 파일은 있는데 읽을 수 없으면(재부팅 후 첫 잠금 해제 전) 던진다. 빈 상태로 시작해 기록을 덮어쓰지 않기 위해서다
struct JSONFile<Value: Codable> {
    let url: URL

    init(directory: URL, name: String) {
        url = directory.appendingPathComponent(name).appendingPathExtension("json")
    }

    /// 없으면 `nil`. 형식이 맞지 않으면 옆으로 옮기고 `nil`.
    func load() throws -> Value? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        do {
            return try JSONDecoder().decode(Value.self, from: data)
        } catch {
            try quarantine()
            return nil
        }
    }

    func save(_ value: Value) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        #if os(iOS)
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
            try data.write(to: url, options: [.atomic])
        #endif
    }

    private func quarantine() throws {
        let stamp = Int(Date().timeIntervalSince1970)
        let base = url.deletingPathExtension().lastPathComponent
        let target = url.deletingLastPathComponent().appendingPathComponent("\(base).unreadable-\(stamp).json")
        try FileManager.default.moveItem(at: url, to: target)
    }
}

/// outbox 상태 파일.
struct FileOutboxStore: OutboxStore {
    let file: JSONFile<OutboxState>

    func load() throws -> OutboxState? { try file.load() }

    mutating func save(_ state: OutboxState) throws { try file.save(state) }
}

enum AppPaths {
    /// `Application Support/WhenIOff`. 없으면 만든다.
    static func dataDirectory(fileManager: FileManager = .default) throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let directory = base.appendingPathComponent("WhenIOff", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// 형식이 맞지 않아 ``JSONFile``이 옆으로 옮겨 둔 파일들.
    static func unreadableFiles(in directory: URL, fileManager: FileManager = .default) -> [String] {
        let names = (try? fileManager.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter { $0.contains(".unreadable-") }.sorted()
    }
}
