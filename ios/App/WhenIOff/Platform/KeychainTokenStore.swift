import Foundation
import Security

/// API 토큰을 Keychain에 둔다 (ADR 0003 §9).
///
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: 재부팅 후 한 번 잠금을 풀었으면 잠긴 기기에서 geofence로
/// 깨어나도 읽힌다. 백업·다른 기기로 옮겨지지 않는다.
struct KeychainTokenStore: TokenStore {
    var service = "com.kangsiwoo.whenioff"
    var account = "api-token"

    enum KeychainError: Error {
        case status(OSStatus)
    }

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    func read() throws -> String? {
        var query = query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw KeychainError.status(status) }
        return String(data: data, encoding: .utf8)
    }

    func write(_ token: String?) throws {
        let deleted = SecItemDelete(query as CFDictionary)
        guard deleted == errSecSuccess || deleted == errSecItemNotFound else { throw KeychainError.status(deleted) }
        guard let token, !token.isEmpty else { return }
        var item = query
        item[kSecValueData as String] = Data(token.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }
}
