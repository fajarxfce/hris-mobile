import Foundation
import MatrixRustSDK

/// Exercises the actual Swift/Rust ABI and encrypted store, without network sync.
public enum LocalCryptoProbe {
    public static func deviceFingerprint(directory: URL, storeKey: Data) async throws -> String {
        guard storeKey.count == 32 else { throw ProbeFailure.invalidKey }
        let store = SqliteStoreBuilder(
            dataPath: directory.appendingPathComponent("store").path,
            cachePath: directory.appendingPathComponent("cache").path
        ).key(key: storeKey)
        let client = try await ClientBuilder()
            .homeserverUrl(url: "http://127.0.0.1:9")
            .disableWellKnownLookup(disableWellKnownLookup: true)
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .requestConfig(config: RequestConfig(retryLimit: 0, timeout: 1_000, maxConcurrentRequests: 2, maxRetryTime: 1_000))
            .sqliteStore(config: store)
            .build()
        try await client.restoreSession(session: Session(
            accessToken: "fictional-offline-probe-not-a-server-token",
            refreshToken: nil,
            userId: "@apple:proof.invalid",
            deviceId: "PROOF_APPLE",
            homeserverUrl: "http://127.0.0.1:9",
            oauthData: nil,
            slidingSyncVersion: .native
        ))
        guard try client.userId() == "@apple:proof.invalid",
              try client.deviceId() == "PROOF_APPLE",
              let fingerprint = await client.encryption().ed25519Key(),
              !fingerprint.isEmpty else { throw ProbeFailure.invalidSession }
        // Swift ARC drops the client/native handles at this function's boundary.
        // No sync service, observer, call or persistent background task is started.
        return fingerprint
    }

    public enum ProbeFailure: Error { case invalidKey, invalidSession }
}
