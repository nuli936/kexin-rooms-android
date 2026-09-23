import Foundation
import Security

struct SavedCredentials: Codable {
    let username: String
    let password: String
}

enum CredentialStore {
    private static let service = "cn.kexin.rooms.ios.credentials"
    private static let account = "school"

    static func load() throws -> SavedCredentials? {
        var query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw SchoolError.message("本机登录信息无法读取，请重新输入。")
        }
        return try JSONDecoder().decode(SavedCredentials.self, from: data)
    }

    static func save(_ credentials: SavedCredentials) throws {
        let data = try JSONEncoder().encode(credentials)
        clear()
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account,
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                    kSecValueData as String: data]
        guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else {
            throw SchoolError.message("无法安全保存本机登录信息。")
        }
    }

    static func clear() {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
    }
}
