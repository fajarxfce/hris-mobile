import Foundation
import MatrixRustSDK
import XCTest

final class ObserverTests: XCTestCase, @unchecked Sendable {
    private func client() async throws -> Client {
        let url = try XCTUnwrap(ProcessInfo.processInfo.environment["MATRIX_OBSERVER_FIXTURE_URL"])
        return try await ClientBuilder()
            .homeserverUrl(url: url)
            .disableWellKnownLookup(disableWellKnownLookup: true)
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .requestConfig(config: RequestConfig(retryLimit: 0, timeout: 2_000,
                                                maxConcurrentRequests: 2, maxRetryTime: 2_000))
            .build()
    }

    func testOriginalResponseAndActualHttpStages() async throws {
        let client = try await client()
        let observer = client.observeHttpRequests()
        defer { observer.stop() }
        let profile = try await client.getProfile(userId: "@observer:fixture.invalid")
        XCTAssertEqual(profile.displayName, "Original profile")
        var start: UInt64?
        var status: UInt16?
        var bytes: UInt64?
        var finished = false
        for _ in 0..<8 where !finished {
            let batch = await observer.nextBatch()
            XCTAssertFalse(batch.closed)
            XCTAssertLessThanOrEqual(batch.events.count, 32)
            XCTAssertEqual(batch.droppedEvents, 0)
            for event in batch.events {
                switch event {
                case .started(let id, let attempt, let time, let request):
                    if request.path.contains("/profile/") {
                        XCTAssertEqual(request.method, "GET")
                        XCTAssertEqual(request.host, "127.0.0.1")
                        XCTAssertEqual(attempt, 1)
                        XCTAssertGreaterThan(time, 0)
                        start = id
                    }
                case .response(let id, let value, _, let names, _):
                    if id == start {
                        status = value
                        XCTAssertTrue(names.contains("content-type"))
                    }
                case .finished(let id, _, let count, let failure):
                    if id == start {
                        bytes = count
                        XCTAssertNil(failure)
                        finished = true
                    }
                }
            }
        }
        XCTAssertNotNil(start)
        XCTAssertEqual(status, 200)
        XCTAssertEqual(bytes, UInt64(Data("{\"displayname\":\"Original profile\"}".utf8).count))
        XCTAssertTrue(finished)
    }

    func testStopWakesPendingReadAndCanRepeat() async throws {
        let client = try await client()
        let observer = client.observeHttpRequests()
        let pending = Task { await observer.nextBatch() }
        await Task.yield()
        observer.stop()
        observer.stop()
        let batch = await pending.value
        XCTAssertTrue(batch.closed)
        XCTAssertTrue(batch.events.isEmpty)
    }

    func testReplacementClosesOldSubscription() async throws {
        let client = try await client()
        let previous = client.observeHttpRequests()
        let replacement = client.observeHttpRequests()
        defer { previous.stop(); replacement.stop() }
        let old = await previous.nextBatch()
        XCTAssertTrue(old.closed)
        XCTAssertTrue(old.events.isEmpty)
        let profile = try await client.getProfile(userId: "@replacement:fixture.invalid")
        XCTAssertEqual(profile.displayName, "Original profile")
        let current = await replacement.nextBatch()
        XCTAssertFalse(current.closed)
        XCTAssertFalse(current.events.isEmpty)
    }

    func testCancelledReaderDoesNotCancelClientRequest() async throws {
        let client = try await client()
        let observer = client.observeHttpRequests()
        defer { observer.stop() }
        let pending = Task { await observer.nextBatch() }
        await Task.yield()
        pending.cancel()
        // UniFFI's generated nonthrowing async API may deliver an empty batch on
        // cancellation. Only termination is required here, not a fabricated error.
        observer.stop()
        _ = await pending.value
        let profile = try await client.getProfile(userId: "@cancelled:fixture.invalid")
        XCTAssertEqual(profile.displayName, "Original profile")
    }
}
