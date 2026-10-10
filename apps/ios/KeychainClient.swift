import Foundation
import Security
import StarterKit

final class KeychainClient: NSObject, AppleCredentialClient {
    private let identity: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: Bundle.main.bundleIdentifier! + ".session",
        kSecAttrAccount as String: "session"
    ]

    func read(completion: @escaping (String?, Error?) -> Void) {
        var query = identity
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { completion(nil, nil); return }
        guard status == errSecSuccess else {
            completion(nil, NSError(domain: NSOSStatusErrorDomain, code: Int(status))); return
        }
        guard let data = result as? Data, let value = String(data: data, encoding: .utf8) else {
            completion(nil, NSError(domain: NSOSStatusErrorDomain, code: Int(errSecDecode))); return
        }
        completion(value, nil)
    }

    func write(value: String?, completion: @escaping (Error?) -> Void) {
        guard let value else {
            let status = SecItemDelete(identity as CFDictionary)
            completion(status == errSecSuccess || status == errSecItemNotFound ? nil : NSError(domain: NSOSStatusErrorDomain, code: Int(status)))
            return
        }
        let attributes: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        var status = SecItemAdd(identity.merging(attributes) { _, value in value } as CFDictionary, nil)
        if status == errSecDuplicateItem {
            status = SecItemUpdate(identity as CFDictionary, attributes as CFDictionary)
        }
        completion(status == errSecSuccess ? nil : NSError(domain: NSOSStatusErrorDomain, code: Int(status)))
    }
}
