# Native application-worker recovery and foreground history

The separate debug-only `apps/collaboration-proof` application resolves the real
shared application container, `CollaborationDeliveryWorker`, SyncTask and
`ChatThreadViewModel`. It uses Android SQLite, protected content keys, encrypted
command/history/receipt repositories, the actual Matrix SDK and real HRIS HTTP
admission. Only login/workspace inputs are seeded fictional fixtures. The direct
worker scenario's scheduler records its task key without executing it; actual
Android scheduling has its separate [WorkManager proof](collaboration-workmanager.md).

## Process and history boundaries

The first process initializes protected sender/recipient records, acquires the
conversation summary through HRIS and queues exact text through
`QueueCollaborationText`. The driver verifies the acknowledged Queued record and
live PID, then force-stops only the proof package on the dedicated emulator.

The second process reopens encrypted application storage and verifies the original
UUID, exact body and Queued state with the sender runtime still closed. After
server admission is enabled, the real worker restores that runtime, sends the
message, acquires its original and retires the command with exact completion
evidence. The peer decrypts its body and logical ID. A repeated worker pass
completes without another encrypted send.

The peer then sends 32 distinct encrypted fixture messages. The actual thread
ViewModel enters normally through a public lifecycle ViewModelProvider. Its newest
original must reach canonical storage while the oldest peer original remains
absent. Up to four explicit older-history actions must finish provider traversal
and recover every exact original. Stopping the screen connection must preserve
the oldest canonical row and clear the incomplete-history notice. No fixture
key refresh or direct history-store insertion bypasses this path.

The server independently verifies 33 encrypted events: one worker event and 32
peer seed events, each with the expected sender and ciphertext. The worker's
single-send invariant, no early sends, receipt/completion retirement and current
application admission checks remain separate from the seed. The seed's test-only
Synapse message burst is 64; other cases keep 10. This is a bounded fixture, not
production capacity evidence.

## Publication race and cleanup

The first foreground runs connected but retained only the original worker message.
Forcing a fresh peer key query made the diagnostic experiment pass. The final
fixture removes that intervention. Platform schema 114 fixes the server race:
key discovery can validate a provider key against its earlier immutable reservation
before the publisher records final readback. Claims and encrypted sends keep their
confirmed-publication requirements. The same APK failed before and passed after
that server correction. See the Platform
[contract](https://github.com/fajarxfce/hris-platform/blob/feat/communications/docs/collaboration/key-publication-race.md).

Fixture cleanup follows the existing product close contract for at most six calls,
retrying only ClosePending. It attempts every resource operation, preserves the
primary exception and attaches cleanup failures as suppressed exceptions. Product
shutdown deadlines are unchanged. Six JVM tests cover bounded drain, non-pending
failure, exception preservation and cancellation. Instrumentation diagnostics
contain only controlled categories, bounded counters and source frames.

## Evidence and continuation

The normal foreground run passed in 94 seconds on API 35 with APK SHA-256
`d685231d6b49439dd4517abc42e072cb0490a8e5bd3326c02a4a726ca6697b55`.
It used two processes, one forced death, one worker pass and four history actions.
All 33 originals were acquired; the oldest remained projected after Stop. Traversal
covered 43 references; the ten remaining references were state-only. Application admission counts were 30 device, 28
conversation and 30 room reads, with zero failures and complete cleanup.

[The measurement](measurements/chat-foreground-native-2026-11-01.json) retains all
failed attempts, the temporary key-query experiment, final source hashes, dependency
verification, 2,733 desktop cases, six cleanup cases, and the distinct SDK/scheduled
regressions. [Earlier direct-worker evidence](measurements/chat-native-worker-2026-11-01.json)
retains the original single-message scenario. Hosted CI acceptance is audited
separately from these local runs.

Build `:apps:collaboration-proof:testDebugUnitTest`, `assembleDebug` and `lintDebug`.
Use the Platform commit pinned by `.github/workflows/collaboration-hris.yml`, Docker,
and the dedicated `hris-mobile-dev` emulator at `emulator-5560`. Set
`HRIS_COLLAB_NATIVE_APK` to the proof APK and run the three `NativeCollaboration*InteropTest`
classes (five cases). Each owns its fixtures and cleanup. Never target the user's phone with this
driver. CI uploads the exact bounded result/checkpoint/trace and test XML artifacts.

No phone UI, live provider deployment, Apple/web runtime, large-scale acceptance or
complete PRD group is accepted by this proof. Continue incoming FCM/inline replies, richer messages, key continuity/backups, calls and
meetings. Local browsing, durable drafts and queued cancellation have separate newer evidence.


## Explicit frozen-draft recovery proof

The direct fixture now preserves ordinary QUEUED recovery and adds a separate FROZEN_DRAFT
case. Before and after actual process death, it verifies zero commands, exact draft revision,
submission ID and body. The worker then admits/sends that original once, archives its receipt,
and clears the matching draft. SDK and WorkManager regressions remain separate cases.

[Native evidence](measurements/chat-frozen-native-2026-11-01.json) records the final four-case,
227s run, exact APK `1a664545`, 44 Mobile/four Platform inputs and forty evidence hashes.
Both workers acquire 33 originals with four history actions and retain the oldest after Stop;
all application admissions succeed. Initial PREPARED-validator and provider-setup failures,
as well as the fixture-method length check failure, are retained with their corrections.
The exact original provider rejection code was lost; no observed 429 claim is made.

CI now pins the proven Platform fixture. Hosted execution must still be audited separately.
Release 8 is already installed; this fixture does not require another phone build. Continue
the reviewed technical-contention versus true edit-conflict UI distinction and remaining PRD.

## Cancelled draft and pending publication recovery

CANCELLED_DRAFT adds a third direct-worker case beside QUEUED and FROZEN_DRAFT. A frozen
original is queued and atomically cancelled before death; exactly one independent command
remains pending. The same cancelled identity/body/draft revision and authenticated archive
survive death, and replay cannot schedule or produce a delivery receipt. Recovery clears the
matching frozen draft; acquired history excludes it. The independent original and 32 peer
seeds account for exactly 33 encrypted sends. No native queue erasure or remote deletion.

[Five-case evidence](measurements/chat-cancellation-native-2026-11-01.json) binds 68 Mobile
and five Platform inputs, the first failed attempt and the correction. Pending sender-key
publication now maps to retryable 503 instead of terminal validation, while missing keys and
live denials retain their gates. All five cases pass with the same Android artifact after
that server correction. Release 10 is installed; these later Mobile edits are fixtures only.

Earlier hosted [frozen recovery](measurements/chat-frozen-native-ci-2026-11-01.json) and
[cancellation-policy regression](measurements/chat-cancellation-policy-regression-native-ci-2026-11-01.json)
remain bound to their four-case sources; neither includes the new pending-publication fix or
explicit cancelled-draft native case. Audit the newly pinned hosted source separately.
