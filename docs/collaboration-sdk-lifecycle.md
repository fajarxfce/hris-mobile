# Product SDK session ownership

This is the implementation continuation for the pinned Android Rust SDK. Product
SDK store/session integration is still required; setup-command durability does
not establish message queue restart acceptance.

The source audit uses Rust SDK
[`8de215a0`](https://github.com/matrix-org/matrix-rust-sdk/tree/8de215a0aa0026afa5754ae5f05a6d168d1a1d60).
The existing actual HRIS fixture establishes encrypted transport and identity
mapping. Its synthetic private store key and proof session JSON are lab-only.

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
proofs pause before enqueue in the same process; they do not establish the required
reopen ordering or durable logical-ID reconciliation. Keep one SDK retry owner.

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
the platform protected credential store; that native repository integration is
the next step, not established by codec tests.

The per-lease `SdkSessionPersistence` helper serializes protected writes and SDK
callback state. Failed or cancelled writes keep their pending value and gate
snapshots until explicit recovery. A committed write whose response was cancelled
retries the same revision. Closing waits for its write owner and rejects late
callbacks. `AndroidCollaborationSdkClientSource` compiles with queues paused before
restore, fixed classic sync, disabled discovery/automatic backup/cross-signing/
pagination, zero transport retries and bounded request concurrency/timeouts.
Actual SDK execution through these new components remains required.

[Component evidence](measurements/collaboration-sdk-storage-2026-10-31.json) retains
final 57s root/assembly/browser checks: 2,058 desktop cases and 19 data browser
cases. Android assembly and quality pass. This is not protected Android store or
message-queue restart acceptance. The preceding setup commit `fb1b1c13` now has a
complete successful hosted control run `41235576905`: desktop 38, browser 30 and
Apple simulator 30 cases, all steps passed and all 39 source hashes verified.

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

Unexpected nonfatal failures in the synchronous void save bridge set a terminal
failure. An in-flight write cannot erase it, even after successfully committing;
ordinary storage failures retain the pending tokens for explicit retry. The
regression exercises that race with a controlled write barrier. Compilation and
shared checks pass; [runtime evidence](measurements/collaboration-sdk-runtime-2026-10-31.json)
does not claim native Keystore execution yet.

Next acceptance: protected store open/reopen and wrong-key preservation on the
dedicated emulator; one pending logical message across process death; receipt loss
and exact SDK transaction reuse; credential rotation with write failure; account
replacement during restoration; cleanup of only the owned resources. Follow with
the Apple adapter/hosted checks, app DI and chat routes. The physical iPhone waiver
does not turn missing Apple implementation into acceptance.
