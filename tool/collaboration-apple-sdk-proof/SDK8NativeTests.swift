import Foundation
import MatrixRustSDK
import XCTest

final class SDK8NativeTests: XCTestCase {
    func testPassphraseIdentityReaderPreservesKeysAndRejectsAnUnrelatedPassphrase() async throws {
        let fixture = try SDK8StoreFixture()
        defer { fixture.remove() }
        fixture.recordFiles("before-first-client")
        let first = try await fixture.fingerprint()
        fixture.recordFiles("after-first-client")
        let restored = try await fixture.fingerprint()
        fixture.recordFiles("after-restored-client")
        XCTAssertEqual(first, restored)
        // Both clients have left their owning function before this blocking, local-only read.
        let identity = try readExistingCryptoIdentity(
            databaseDirectory: fixture.directory.appendingPathComponent("store").path,
            passphrase: fixture.passphrase
        )
        XCTAssertEqual(identity.userId, "@apple:proof.invalid")
        XCTAssertEqual(identity.deviceId, "PROOF_APPLE")
        XCTAssertEqual(identity.ed25519Key, first)
        XCTAssertFalse(identity.curve25519Key.isEmpty)
        fixture.recordFiles("after-existing-identity-read")
        do {
            _ = try readExistingCryptoIdentity(
                databaseDirectory: fixture.directory.appendingPathComponent("store").path,
                passphrase: UUID().uuidString
            )
            XCTFail("An unrelated passphrase opened the retained identity")
        } catch {
            XCTAssertTrue(error is ExistingCryptoIdentityError)
        }
        fixture.recordFiles("after-unrelated-passphrase-refusal")
        let afterRefusal = try await fixture.fingerprint()
        XCTAssertEqual(afterRefusal, first)
        fixture.recordFiles("after-final-client")
    }

    func testMissingStoreReadDoesNotCreateFiles() async throws {
        let fixture = try SDK8StoreFixture()
        defer { fixture.remove() }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.directory.path).isEmpty)
        do {
            _ = try readExistingCryptoIdentity(databaseDirectory: fixture.directory.path, passphrase: fixture.passphrase)
            XCTFail("Missing store returned a fabricated identity")
        } catch {
            XCTAssertTrue(error is ExistingCryptoIdentityError)
        }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.directory.path).isEmpty)
    }

    func testExistingIdentityRejectsParentSymlinkWithoutChangingRetainedKeys() async throws {
        let fixture = try SDK8StoreFixture()
        defer { fixture.remove() }
        let first = try await fixture.fingerprint()
        let alias = fixture.directory.appendingPathComponent("alias")
        try FileManager.default.createSymbolicLink(
            at: alias, withDestinationURL: fixture.directory.appendingPathComponent("store")
        )
        XCTAssertTrue(FileManager.default.fileExists(atPath: alias.appendingPathComponent("matrix-sdk-crypto.sqlite3").path))
        do {
            _ = try readExistingCryptoIdentity(databaseDirectory: alias.path, passphrase: fixture.passphrase)
            XCTFail("A redirected parent opened the retained identity")
        } catch {
            XCTAssertEqual(error as? ExistingCryptoIdentityError, .Unavailable)
        }
        let restored = try await fixture.fingerprint()
        XCTAssertEqual(restored, first)
    }

    func testSDK8ListenerCancellationAndObserverCloseRetainNoListener() async throws {
        let fixture = try SDK8StoreFixture()
        defer { fixture.remove() }
        try await fixture.exerciseOwnedSubscriptions()
        // A fresh client reopens the same encrypted store after all listener/client owners leave.
        let reopened = try await fixture.fingerprint()
        XCTAssertFalse(reopened.isEmpty)
    }
}

/// Owns only a temporary offline SDK store. No product account, token or device is used.
private struct SDK8StoreFixture {
    let directory: URL
    let passphrase = UUID().uuidString + UUID().uuidString

    init() throws {
        // macOS's system temporary URL can use /var -> /private/var. Resolve the OS-owned
        // parent before creating our private leaf; retain the SDK's NOFOLLOW guard inside it.
        directory = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]
        )
    }

    func remove(file: StaticString = #filePath, line: UInt = #line) {
        recordFiles("before-cleanup")
        do {
            try FileManager.default.removeItem(at: directory)
            XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path), file: file, line: line)
            print("SDK8 fixture cleanup completed")
        } catch {
            recordFiles("cleanup-failed")
            let failure = error as NSError
            XCTFail("SDK8 fixture cleanup failed: \(failure.domain)/\(failure.code)", file: file, line: line)
        }
    }

    /// File metadata only, inside this fictional fixture. Never print keys, tokens or file bodies.
    func recordFiles(_ phase: String) {
        let manager = FileManager.default
        var files = [directory]
        if let contents = manager.enumerator(at: directory, includingPropertiesForKeys: nil) {
            for case let file as URL in contents {
                if files.count == 64 { break }
                files.append(file)
            }
        }
        print("SDK8 fixture phase: \(phase)")
        for file in files {
            do {
                let attributes = try manager.attributesOfItem(atPath: file.path)
                let name = file == directory ? "." : String(file.path.dropFirst(directory.path.count + 1))
                let mode = attributes[.posixPermissions] ?? "absent"
                let immutable = attributes[.immutable] ?? "absent"
                let appendOnly = attributes[.appendOnly] ?? "absent"
                print("SDK8 fixture file: \(name), mode=\(mode), immutable=\(immutable), appendOnly=\(appendOnly)")
            } catch {
                let failure = error as NSError
                print("SDK8 fixture metadata failure: \(failure.domain)/\(failure.code)")
            }
        }
    }

    func client() async throws -> Client {
        let store = SqliteStoreBuilder(
            dataPath: directory.appendingPathComponent("store").path,
            cachePath: directory.appendingPathComponent("cache").path
        ).passphrase(passphrase: passphrase)
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
        return client
    }

    func fingerprint() async throws -> String {
        let client = try await client()
        let identity = await client.encryption().ed25519Key()
        return try XCTUnwrap(identity)
    }

    func exerciseOwnedSubscriptions() async throws {
        let client = try await client()
        let position = await client.storedSyncPositionV2()
        XCTAssertNil(position)
        let observation = client.observeHttpRequests()
        defer { observation.stop() }
        let pendingObservation = Task { await observation.nextBatch() }
        observation.stop()
        observation.stop()
        let closed = await pendingObservation.value
        XCTAssertTrue(closed.closed)
        XCTAssertTrue(closed.events.isEmpty)

        var listener: SDK8KeyHintRecorder? = SDK8KeyHintRecorder()
        weak var releasedListener = listener
        let registration = await client.encryption().subscribeToRoomKeyChanges(listener: listener!)
        let owned = try XCTUnwrap(registration)
        defer { owned.cancel() }
        for _ in 0..<200 {
            if listener?.reconciliationReceived == true { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(listener?.reconciliationReceived == true)
        owned.cancel()
        owned.cancel()
        for _ in 0..<200 {
            if owned.isFinished() { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(owned.isFinished())
        listener = nil
        XCTAssertNil(releasedListener)
    }
}

private final class SDK8KeyHintRecorder: RoomKeyChangesListener, @unchecked Sendable {
    private let lock = NSLock()
    private var received = false

    var reconciliationReceived: Bool {
        lock.lock()
        defer { lock.unlock() }
        return received
    }

    func onUpdate(changes: RoomKeyChangeBatch) {
        lock.lock()
        defer { lock.unlock() }
        received = received || (changes.reconcileRequired && changes.roomIds.isEmpty)
    }
}
