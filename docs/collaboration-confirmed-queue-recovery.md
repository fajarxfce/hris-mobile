# Confirmed native queue recovery

After a send is accepted, interrupted recovery can leave its native transaction
behind while the application archives the completed command. A later send then
encounters an unmapped native transaction and is held. Local settlement removes
that proven original before the worker prepares subsequent delivery.

`CollaborationDeliveryWorker` passes its admitted timeline to
`SettleCollaborationCompletedQueue`. The use case borrows that observer, owns a
separate exclusive queue lease, and rechecks the current account, company,
native session family, device and read admission around repository operations.
It closes its queue even when acquisition finishes after cancellation. It does
not fetch room metadata, reopen authorization or start synchronization.

Active staged/sent commands and uniquely recoverable uncertain enqueues keep
their existing ownership. Unmapped originals require an immutable completion
record matching the logical ID, conversation, sender, full content hash, native
transaction, device and accepted event. Legacy bootstrap aliases remain confined
to the original native family. A transaction referenced by an uncertain enqueue's
before-snapshot remains protected. Missing proof never authorizes removal.

The Android datasource reads original event type and exact JSON bytes from the
paused SDK queue. The repository maps these raw facts; its mapper hashes the
complete canonical content, including unknown fields, while retaining the raw
bytes for native comparison. The use case validates the complete snapshot and
all completion proofs before settlement, then rechecks active commands before
each write. Limits are128 originals,96,000 UTF-8 bytes each and1,048,576 total.

The patched SDK rejects enabled/in-flight queues, changed content and dependent
work. It removes only the matching original and emits a local completion; it does
not send, cancel or request server redaction. Repeated settlement returns absent.
The native API does not authenticate a supplied event ID: application completion
provenance remains mandatory. Ordinary SDK abort is unsuitable because aborting
an in-flight send may schedule a server redaction.

[Application evidence](measurements/collaboration-confirmed-queue-application-2026-11-06.json)
contains45 passing cases with real encrypted application stores and a fake SDK.
[SDK evidence](measurements/collaboration-sdk-confirmed-queue-2026-11-06.json)
contains8 core and19 FFI cases, including SQLite reopen, cancellation, concurrency,
exact-content comparison and absence of send/redaction requests.

Android consumer and actual HRIS native acceptance passed on6 November2026:
380 initial host cases,18 fresh proof cases after the fixture correction, and both
HRIS native scenarios. [Native evidence](measurements/collaboration-confirmed-queue-android-consumer-2026-11-06.json)
retains earlier setup failures separately. The
new native scenario deliberately sends the same content through a separate SDK
request while retaining its paused queue original, then restarts the process twice
and settles offline. This constructs the recovery postcondition; it does not
simulate a literal lost HTTP response or prove the user's phone root cause.
The normal shipping dependency and application sources now contain this increment;
release43 does not. Release44 acceptance remains pending.

The native test candidate has arm64 and x86_64 libraries. All four Android ABI
outputs are independently verified, packaged and published with strict dependency
hashes. The normal dependency gate passes; nondebuggable release acceptance remains. Existing
release variants do not enable R8, so no minification claim is made. The user’s
phone is still on42 and accepted release43 awaits ADB availability.
