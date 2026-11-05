# Device sync before the first conversation

An enrolled device previously needed room activity to start the native sync loop.
A new employee with no provider room and no outgoing messages could therefore
finish registration/bootstrap without publishing encryption keys. The server
correctly refused encrypted delivery to that recipient.

Company recovery now synchronizes the already-enrolled device even when its
conversation directory is empty or a direct room is `NOT_REQUESTED`. The native
SDK owns key creation/upload. This does not enroll another device, create/join a
room, resume message queues or require a synthetic message.

`CollaborationDeviceRecovery` owns a finite background reference and current
access observation. `SynchronizeCollaborationDevice` admits the exact parent,
company, platform and active device before and after I/O, preserving access
denials. The existing Android runtime owns the sync producer; waiters observe its
first successful sync response. Cancellation or timeout removes the waiter;
shutdown still drains native work before releasing the exclusive SDK store.
Concurrent waiters share that producer.

Device synchronization and incoming recovery run as joined children with their
own 15-second turns. Outgoing delivery and push registration follow with their
existing turns. The critical path stays 45 seconds inside the existing 50-second
company budget; none of these operations renews the original admission. Runtime
acquisition contention remains retryable. Cancellation joins both initial
children and does not start outgoing work.

Sync completion is **not** a key-publication acknowledgment. The pinned Matrix
SDK logs individual encryption-upload failures and may still return a successful
sync response ([upstream implementation](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/crates/matrix-sdk/src/client/mod.rs#L3398)).
The existing SDK retry cycle and subsequent recovery attempts remain responsible
for transport retry. The server still requires its fenced public-key readback
before admitting a recipient. No readiness guard or encryption policy is relaxed.

Validation covers no-room/no-command recovery, current ownership and denial,
concurrent incoming progress, finite budgets, late responses and joined cleanup.
Android host cases use inert FFI handles and do not prove native key upload.
The release requires a separate actual product/SDK check on the retained fictional
sample device, verifying server publication before any provider room or message.
This HRIS/Matrix integration does not belong in the generic Starter.

[Validation record](measurements/chat-device-sync-2026-11-05.json): final source gate
passes 3,664 fresh cases, zero failures/errors/skips, in 521.721 seconds. All 4,077
inputs stayed unchanged. The earlier failed fixture expectation is recorded. Native
release acceptance is recorded below.


## Actual release36 acceptance

The exact non-debuggable ARM64 APK updates the retained sample1 installation.
Immediately before update: one ACTIVE device, zero confirmed publications and no
provider-room request/binding. After normal Home startup: one confirmed server
publication, same device, still no provider room or sent message. Cold restart
preserves that server-published identity; the app returns to Home. This is actual
product/SDK/server execution, not the inert FFI host fixture. Probe24.456s;
observation timing is not an upload-latency measurement.

The same APK on retained Bima passes in-place update, two room recreations and cold
restart: eight original rows, no Older messages, undecryptable placeholder or
history-error control. Proof102.333s. Closed diagnostics recorded76 HTTP200 results
and three unavailable worker retries at startup; zero-crash evidence does not claim
zero retries. Source/build/lint pass; phone install-only delivery is confirmed with
matching device SHA256 and retained app identity. [Full evidence](measurements/android-release-phone-2026-11-05-build36.json).
