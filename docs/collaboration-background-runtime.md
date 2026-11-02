# Finite background runtime references

The application retains the current native parent independently of its selected
company. A valid parent permits a separately admitted background operation to
borrow a company's existing Android runtime or restore its protected store. It
does not authorize reading, sending, enrollment, synchronization or pusher
activation. Those operations still require their own current admission.

`AcquireCollaborationBackgroundRuntime` accepts the existing ephemeral company
admission without consulting or changing `WorkspaceRepository`. It checks the
current parent and retained company denial before acquisition, checks them again
afterward, validates the returned owner/device and publishes synchronously before
transferring the reference. Cancellation, expiry, changed ownership and publication
failure leave cleanup with the acquiring caller. An absent protected identity is
an explicit null result; background recovery never enrolls a replacement device.

The request's original monotonic deadline is carried through admission and runtime
acquisition, alongside its calibrated wall-clock deadline. Network and acquisition
time consume the same maximum 60-second budget. Moving the wall clock backward
cannot extend it. This deadline is process-local and is never serialized as a
durable permission or used to expose local content.

## Native resource ownership

The Android registry reserves the protected store identity before suspending SDK
construction. It permits at most five native reservations, including acquisitions
and unfinished shutdowns, and two background references with no waiting queue.
Only one background reference may use a particular reservation at a time. A
foreground entry can retain an already published background-created client without
receiving a second parent close handle.

A background reference owns its finite timer. Releasing it leaves a foreground
client available. Changing the selected company drops foreground retention but
preserves an admitted background reference until it ends. Parent replacement,
logout, hard expiry, application collector disposal and confirmed company denial
invalidate affected reservations and late acquisitions. Completed SDK acquisition
still requires explicit publication. Retired late clients remain attached to the
original reservation until their native drain finishes.

Native shutdown waits for acquisition to stop using the client before pausing it,
closing its database and releasing the directory lock. A failed or still-pending
drain remains registry-owned and consumes capacity; a later explicit operation can
retry it. The timer never becomes an unowned retry loop. History reopening reserves
an exclusion marker for its entire suspending decision, so it cannot reclaim a
client concurrently with background borrowing. Completion clears only its original
reservation's marker.

`MaintainCollaborationRuntimeOwnership` observes parent and foreground deadlines
separately. Foreground admission expiry does not cancel the later hard-parent timer.
A workspace read failure drops foreground retention; an identity failure drops the
parent. Confirmed company/MFA denial remains in the local admission gate even when
native cleanup fails. Repeated observations of the same parent preserve references.

## Validation and continuation

[The measurement](measurements/chat-background-runtime-2026-11-02.json) records
focused domain, Android host, actual DI and proof APK checks, required root/browser
checks and the separately executed native scenarios.
Host tests with inert FFI handles exercise publication, pending acquisition,
reference expiry, cancellation and failed native drain; they are not JNI evidence.

The native proof fixture additionally restores the real encrypted SDK store through
a background-only reference, verifies private acquisition before publication,
releases the final reference, reopens the same cryptographic identity and verifies
that releasing a shared reference preserves its foreground client. Its admission
is controlled fixture input. This proves native resource ownership, not incoming
FCM or a completed background recovery worker.

Two existing browser correctness fixtures persist and traverse more than 500
records. They exceeded Mocha's default two-second budget under concurrent host load.
The data module now has a finite ten-second browser-test budget; all assertions and
product deadlines are unchanged. The generated Karma configuration includes it.
The measurement retains the failed runs, the successful browser retry, the initial
native emulator's kernel-confirmed OOM and the separate native retry results.

Continue typed Selected/Background scope through the existing history/outbox use
cases, followed by durable all-company wake/reconnect/periodic recovery and incoming
notification projection, privacy, read/mute handling, deep links and inline reply.
Keep backend delivery disabled until actual receive/recovery acceptance. There is
no Apple/web runtime, live rollout, phone installation or complete PRD acceptance
in this increment. This HRIS ownership policy is not a generic Starter port.
