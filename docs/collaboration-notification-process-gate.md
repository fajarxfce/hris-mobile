# Native notification process gate

The surface owner exposes a cold advisory stream for process coordination. Pending and
Blocked permit no publication; Ready identifies the current parent and an opaque epoch,
with an idle flag and execution revision. Lock acquisition and release wake consumers.
The actual operation still checks the current parent, epoch and mutex; observed readiness
is neither a content grant nor a lease. Late collectors see Closed after disposal.
Closed forbids new turns; `closeAndJoin` separately waits for work and cleanup.

`pause(parent)` closes the epoch before cancelling active work. It attempts joined exact
compensation and serialized namespace cleanup without needing account decryption keys.
Its two-second cooperative budget includes waiting for the current turn and cleanup.
Success, failure and timeout all retain Blocked until explicit `reconcile` succeeds.
A timeout does not assert that an outstanding native call has stopped: serialization and
the blocked gate continue to exclude new publication. Native IPC is not a database
transaction and a cooperative timeout cannot interrupt an uncooperative OS call.

An old parent cannot pause the replacement parent. A pending pause cannot reopen that
replacement after cleanup. Cancellation of the caller still attempts cleanup, propagates
cancellation and retains the closed gate. Calling pause from inside a lease is rejected
because it would have to join its own request. Explicit recovery creates a new epoch.

Eight new virtual-time cases, with a fake OS, cover late collection/close-before-start,
pending startup and busy completion, key-independent pause, joined publication cleanup,
failed/timed-out cleanup without automatic retries, cancellation, parent replacement and
rejection of a nested pause. The owner suite has 30 cases. [Evidence](measurements/chat-notification-process-gate-2026-11-03.json)
records focused and full checks, then targeted validation of the final copy-visibility
annotation. Full-check reports retain their original source hashes.

This is a process coordination primitive. The bounded journal/source coordinator,
candidate delivery, focused-thread suppression, authenticated native callbacks and
retained-input review remain integration work. No HRIS native host or chat push is
activated here, and no new product APK is installed.
