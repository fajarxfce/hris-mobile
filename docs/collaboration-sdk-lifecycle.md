# Product SDK session ownership

This is the implementation continuation for the pinned Android Rust SDK. Native
runtime composition now has protected store reopening, application admission/DI,
lease-owned synchronization and [owned room/timeline observers](collaboration-timeline.md).
Actual SDK queue process-death evidence is retained. Product content routes,
owned offline projections and logical-message reconciliation beyond provider
transaction retention remain in progress.

The source audit uses Rust SDK
[`8de215a0`](https://github.com/matrix-org/matrix-rust-sdk/tree/8de215a0aa0026afa5754ae5f05a6d168d1a1d60).
The actual HRIS fixture now uses the protected Android runtime and verifies
identity preservation before encrypted transport. The separate provider-only
fixture's synthetic private store key and session JSON remain lab-only.

The product owner needs an exclusive store lock, a platform-protected passphrase
and a durable session record bound to HRIS audience/account/company/native family,
Matrix user/device and the configured gateway origin. Gateway token rotation or
app build changes must not create another crypto store. Missing/unreadable keys
must preserve the original files; no automatic reset or provider logout is valid.
Open exactly one SDK client per store lifetime and restore exactly once. A failed
open must close the client and release the owned lock without deleting data.

The pinned FFI exposes `Client.enableAllSendQueues(false)` independently of sync.
Product restoration must prove that queues can be paused before session restore
and queue-listener attachment, then explicitly resumed after current admission.
The send-queue update listener respawns tasks for stored unsent events. Current
process-death proof verifies this ordering and the original queued transaction.
Durable application logical-ID reconciliation remains separate. Keep one SDK retry owner.

`homeserverUrl` plus `disableWellKnownLookup(true)` prevents server discovery from
redirecting the configured gateway. Cross-signing, automatic backups and background
pagination need explicit configuration. Matrix auth restore starts background
E2EE setup tasks, including backup/recovery setup, so restoring a local store must
not be described as a proven zero-network operation. Local readiness, live request
admission and bounded request cancellation need separate verification.

The pinned `ClientSessionDelegate.retrieveSessionFromKeychain` returns a fallible
result through FFI; `saveSessionInKeychain` returns void. Rust's save callback calls
it and returns success without a persistence acknowledgement. Production handling
must therefore track a failed credential write, retain its owned pending value,
gate further delivery and reconcile persistence explicitly. Do not throw an
unhandled foreign callback exception or report an in-memory token as durably
installed. Exercise write failure, process death and recovery without discarding
the crypto store. Closing native resources inside a callback also needs explicit
ownership to avoid deadlocking its caller.

## Implemented storage components

`FileCollaborationSdkStoreSource` acquires an OS file lock and returns retained
database/cache paths. It checks path identity, probes occupancy without listing an
unbounded directory, and releases acquired resources after preparation or coroutine
handoff failure. Closing never deletes content. Tests cover competing JVM handles,
separate identities, symlinks, failed preparation and cancellation after acquisition.
The platform must supply its private backup-excluded root.

`CollaborationSdkRecordCodec` authenticates the expected scope before use. A
SHA-256 namespace binds API audience/account/company/native family/device and the
explicit root gateway URL, excluding the replaceable local session and tokens.
Records pin the complete identity, passphrase, bootstrap origin and credential
revision. Rotation preserves the passphrase and initial deadline facts; it does
not invent a newer admission timestamp. The serialization is intended only for
the platform protected credential store. Codec tests establish the record contract;
the protected Android fixture separately establishes the native integration.

The per-lease `SdkSessionPersistence` helper serializes protected writes and SDK
callback state. Failed or cancelled writes keep their pending value and gate
snapshots until explicit recovery. A committed write whose response was cancelled
retries the same revision. Closing waits for its write owner and rejects late
callbacks. `AndroidCollaborationSdkClientSource` compiles with queues paused before
restore, fixed classic sync, disabled discovery/automatic backup/cross-signing/
pagination, zero transport retries and bounded request concurrency/timeouts.
Actual SDK execution through these components now passes in the protected fixture.

[Component evidence](measurements/collaboration-sdk-storage-2026-10-31.json) retains
final 57s root/assembly/browser checks: 2,058 desktop cases and 19 data browser
cases. Android assembly and quality pass. This is not protected Android store or
message-queue restart acceptance. The preceding setup commit `fb1b1c13` now has a
complete successful hosted control run `41235576905`: desktop 38, browser 30 and
Apple simulator 30 cases, all steps passed and all 39 source hashes verified.

The [durable handover mechanism](collaboration-runtime-handover.md) adds immutable
schema2 intent metadata and excludes legacy acquisition after staging. Its local
intent does not authorize server freeze or complete group runtime adoption.

## Android runtime composition

`androidCollaborationRuntimeRepository` supplies the platform Keystore adapter and
the application's backup-excluded directory. Runtime install/restore owns the
client before restore can start background work. Replaying the same bootstrap
keeps the latest protected SDK tokens; a newer bootstrap must preserve the Matrix
identity and store passphrase. An occupied store without a readable record fails
without generating another key. Local session replacement does not change its
audience/account/company/native-family/device namespace.

The lease owns credential callbacks, their failure collector and the one wait for
native E2EE initialization. Closing pauses queues, rejects late credential access
and waits for that initialization before releasing client/directory resources.
Cancellation of a caller's wait cannot detach native initialization and unlock its
SQLite files. Close reports pending after five seconds; the application repository
retains the lease until cleanup completes, with a capacity of five. SDK request
timeouts are finite, but this does not prove termination of every native SDK bug.
Queue/sync/room listener owners must stop before closing the lease.

The deeper send-queue audit found that `set_enabled(false)` explicitly does not
abort an already running send. The current implementation therefore also calls
SDK `Client.pause()` after initialization and before native close/directory unlock.
At the pinned Rust commit, `crates/matrix-sdk/src/client/mod.rs:4056` disables queues
and awaits `base_client().close_stores()`. A failed drain retains the client and
exclusive lock; another explicit close may retry. A completed release is never
repeated, even when it reported an error. Shared shutdown tests cover competing
callers, timeout, cancellation, failed drain and repeated disposal. Actual execution
through the protected HRIS fixture now passes; see the
[protected-store evidence](measurements/collaboration-protected-native-store-2026-10-31.json).

In the process-death fixture, the pinned queue's `send_serialized` awaits
`QueueStorage.push` before publishing `NewLocalEvent`. Global queue subscription
replays retained local events. Pause precedes restore and subscription; the test
must kill the owned Android process after the durable local receipt, restore the
same protected record/store in another process, observe the same transaction and
then explicitly resume it. This now passes in 33.512s through the actual HRIS
gateway with zero encrypted sends before release; both protected device identities
and the original SDK transaction are unchanged. The driver verifies the old PID is
gone and a different live PID restored it. It never reinstalls or replaces the
message. See [evidence](measurements/collaboration-sdk-process-death-2026-10-31.json).
Keep SDK store-error retry behavior under a bounded application owner; the SDK
itself backs off and retries store reads. App logical-message reconciliation and
server transaction-retention limits remain separate from SDK queue persistence.

Unexpected nonfatal failures in the synchronous void save bridge set a terminal
failure. An in-flight write cannot erase it, even after successfully committing;
ordinary storage failures retain the pending tokens for explicit retry. The
regression exercises that race with a controlled write barrier. Compilation and
shared checks pass; [runtime evidence](measurements/collaboration-sdk-runtime-2026-10-31.json)
records compilation and shared checks; native execution is retained in the separate
protected-store measurement.

An explicit runtime open now retries each previously closing lease once before
capacity acquisition. Failed drains keep their ownership and count against the
five-lease bound; live leases are untouched. Unexpected nonfatal retrieval-bridge
errors become the declared SDK exception and set the terminal credential gate.

Next acceptance: admitted bootstrap installation/recovery; credential rotation with
write failure; account replacement during restoration; app outbox reconciliation
and receipt loss beyond the provider transaction lifetime. Follow with the Apple
adapter/hosted checks, app DI and chat routes. The physical iPhone waiver does not
turn missing Apple implementation into acceptance.

## Application resource lifetime

The application container now installs collaboration data/use-case bindings and
owns one platform runtime factory. Android supplies the application Context,
Keystore records and backup-excluded directory, using the configured HRIS root
with cleartext allowed only in Dev. Hosts without a binding return an explicit
unavailable result instead of creating a substitute native store.

`ApplicationCollaborationLifetime` starts once from Android's Application. It owns
its coroutine independently of activities/routes, retains observation failures in
state and cancels observation on container disposal. `MaintainCollaborationRuntimeOwnership`
admits one collector, observes session/company changes and asks the runtime to
close obsolete owners. A missing selection, observation failure or denied authority
closes all resources. Native close retains its exclusive lock through unfinished
cleanup; it does not remove credentials, databases, history or pending messages.

Each admitted frame has one timer bounded by native parent, workspace/MFA,
company policy and scheduled maintenance. Its elapsed timer expires that frame
even if the wall clock moves backwards. A new repository emission revalidates
its metadata and replaces the timer. Ordinary token rotation preserves the owner;
there is no polling or automatic SDK reopen. Superseded cleanup responses cannot
publish old ownership. Cancellation closes all owned runtimes before releasing
the single-collector guard. Confirmed denials override cleanup failures.

Eight common lifecycle cases and two assembled application binding cases pass.
[Application evidence](measurements/collaboration-application-runtime-2026-10-31.json)
retains root, Android and Wasm compilation plus Android lint. An existing manifest
reference required a direct AppAuth dependency; the app now declares the catalog's
existing version and CI runs lint. No runtime library version changed.

The application currently maintains SDK resource ownership only. Before enabling
live chat, sync services and room/listener children must join the same owned
shutdown path. Native setup/restore is not an offline message projection or a
delivery grant. Conversation controls/UI, bootstrap uncertainty/build recovery,
logical-message reconciliation, Apple runtime and all remaining P0–P8 still apply.

## Lease-owned classic synchronization

`SdkSyncLifecycle` owns one raw connection per protected runtime. Concurrent starts
share acquisition even when a caller disappears or its five-second wait expires.
Only explicit starts retry failed acquisition. The connection owns one transport
pump; repeated starts never create competing requests or reset its retry budget.

The pinned FFI `Client.syncV2` loop terminates on the first failed sync and only
logs the error. Hosted HRIS run `41250076331` exposed this after a provider-capacity
429: only one encrypted direction completed. The adapter now drives
`syncOnceV2` with a ten-second long poll. Each operation uses the SDK's persisted
cursor (`SyncToken::ReusePrevious`); Kotlin never supplies/resets that cursor or
replaces the store. SDK processing still handles E2EE responses and persistence.

Transient transport failures get at most five retries after the initial request,
with exponential 1/2/4/8/16-second backoff plus up to 250ms jitter. Retry-After is
never shortened. A delay above 30 seconds pauses until explicit restart after its
deadline. Exhaustion pauses this same connection; no implicit acquisition, token
renewal, business-command replay or device reset occurs. Successful requests clear
the transient failure and reset the budget. Fast successful polls have a one-second
minimum interval. Structured authentication, access and request-shape errors stop
automatically; the same connection cannot restart a terminal failure.

The datasource owns this bounded protocol scheduling; repositories only own the
connection and map its sanitized failures. This is distinct from application read
refresh and command retry policies. Raw provider descriptions are never retained.
Authorization failures gate timeline snapshots, pause send queues and close child
observers; ordinary transport failure leaves retained snapshots available.

Stop wakes backoff immediately and prevents another request, but waits for an
in-flight SDK operation to finish without cancelling its foreign coroutine. The
parent close still returns after at most five seconds while its shutdown producer
retains the client and exclusive store lock. Database drain and unlock follow
actual completion. A cancelled close waiter cannot release the store early. A final authentication
or access denial is retained even if its observer is being disposed or native
termination fails.
Credential persistence failure also stops sync; fixing its write does not restart
it. Reopen under current admission. Starting a pump is not evidence of connectivity
or authorization and never enables message delivery.

The actual HRIS fixture injects one `M_LIMIT_EXCEEDED` response only after process
death and store restoration. It compares cursor digests in memory and requires a
successful retry with the same cursor, then both decrypted replies and complete
cleanup. Cursor values, digests and credentials are excluded from the report.
The original transaction and device identity must survive throughout. Product
routes, offline projections and logical-message reconciliation remain separate.

## Application entry reuse

`EnsureCollaborationRuntime` admits the current HRIS owner and checks `isOpen`
without handing a parent close capability to the route. An existing client is
reused; otherwise the shared acquisition instance restores a paused stored client
once. False means an explicit bootstrap is needed. Neither branch enrolls a device,
starts synchronization, resumes delivery or resets protected keys. Missing native
bindings report unavailable rather than pretending bootstrap would work.

The repository publishes entry availability only after SDK restoration and durable
initialization finish. A registered but incomplete client reports opening; it cannot
be used for timeline entry. Closing clients also stop being available. All resources
remain registered with the application owner through pending cleanup.

Owner checks follow the probe and restoration. Late/cancelled/mismatched restoration
closes only the lease created by that attempt. A reused application parent is never
closed by a cancelled route. Received and owned credential denial survive failed
post-request reads and failed disposal. The use case returns scoped readiness,
not a raw SDK client or parent lease. Existing explicit install/restore APIs retain
their separate ownership contracts for setup and recovery.
