# Space provider history through the installed original runtime

`CollaborationSpaceProviderHistoryRepository.readPage` exposes one bounded backward metadata
page from the existing Android runtime. The response carries both the exact logical Space
runtime and the original physical Company owner/device. It never relabels original credentials
or provider cursors, and creates no client, join, timeline, synchronization or message queue.

The Android repository resolves the exact published owner/device/operation before reading and
checks it again after suspension. Credential failures retain their classification, obsolete
runtime results cannot publish, and caller cancellation propagates. The finite child uses the
current SDK token, independent `from`/`to` bounds, a20-event limit and the existing HTTP pool.
Parent shutdown cancels and drains that child before closing its SDK client/database. A failed
read cannot become an empty page. Hosts without the native capability return Unavailable.

The data module binds this capability to its single application runtime. Current content
admission, atomic provider/reference retention and the incoming consumer remain separate;
this capability alone does not advance a cursor or enable product synchronization.

Six new Android parent/child cases exercise exact physical identity, unsorted provider order,
opaque bounds, token rotation, invalid-bound exclusion, parent/caller cancellation, independent
follow-up reads,401/403 classification and late credential revocation. Common availability
coverage also checks missing implementations and cancellation, and application DI verifies the
same runtime instance. Actual SDK execution of this new page wrapper is still pending.

Full1/97602 completed successfully in933.751s. The strict audit verifies10,327 cases:
8,308 freshly executed and2,019 exact previously accepted controls, with no failures, errors or
skips. Root check, Android host/release compilation/lint, actual Chromium, iOS application source,
proof APK/unit tests, formatting, architecture and detekt pass. Source and library manifests stayed
fixed throughout. [Measurement](measurements/collaboration-space-provider-history-2026-11-10.json).
The proof APK is an acceptance artifact, not a phone installation or actual native execution of
this new wrapper. Durable Space provider progress and its consumer are the next implementation.
