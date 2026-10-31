import Foundation
import Security
import XCTest
@testable import HrisCollaborationAppleProof

final class LocalCryptoProbeTests: XCTestCase {
    func testNativeCryptoIdentitySurvivesReopenAndRejectsUnrelatedStoreKey() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: directory) }
        let key = try randomKey()
        let first = try await LocalCryptoProbe.deviceFingerprint(directory: directory, storeKey: key)
        let restored = try await LocalCryptoProbe.deviceFingerprint(directory: directory, storeKey: key)
        XCTAssertEqual(first, restored)
        do {
            _ = try await LocalCryptoProbe.deviceFingerprint(directory: directory, storeKey: try randomKey())
            XCTFail("A different store key opened the existing device identity")
        } catch {
            // The SDK must refuse an unrelated key; do not publish its diagnostics.
        }
    }

    private func randomKey() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw LocalCryptoProbe.ProbeFailure.invalidKey
        }
        return Data(bytes)
    }
}
