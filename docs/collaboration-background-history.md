# Background conversation entry and history

`EnterCollaborationConversation`, `AcquireCollaborationHistory` and
`AcquireCollaborationProviderHistory` accept the shared
`CollaborationOperationScope`. Existing String entry points delegate to Selected;
Background captures the current native parent and the original finite company
admission without reading or changing workspace selection. An admission for one
device cannot be reused with another requested device.

The same acquisition implementation handles both scopes. It preserves setup,
conversation membership and room checks; the original-window bounds and batches
of at most16 changes; provider-page validation and atomic progress comparison;
and retained denial checks after suspending operations. The process-local monotonic
deadline is never renewed by entering a room or reading another page. A valid
operation40seconds into its original budget can continue; a result after expiry
or parent replacement cannot publish new content or advance a page cursor.

Native timeline acquisition retains responsibility for a late child until all
ownership checks pass. Failure or cancellation closes that child without handing
it to another caller. History acquisition leaves already committed local facts
and progress intact if ownership changes afterward. Received company denial is
retained even when the invoking coroutine is cancelled or cache cleanup fails.
An ordinary expired grant does not become a company denial or delete old history.

The caller must already hold a published, finite
[background runtime reference](collaboration-background-runtime.md) for the operation.
These use cases do not restore/enroll a device or extend that reference's lifetime.
They do not generalize foreground history-runtime reopening: reclaiming a client
that is currently borrowed remains excluded. Local history observation keeps its
separate owned-facts contract and does not acquire a background permission deadline.

[Validation](measurements/chat-background-history-2026-11-02.json) records the
13 added scope/cancellation cases and required checks. Native entry uses a controlled
lease fixture; this increment adds no new JNI, incoming FCM, device-installation or
full background-worker acceptance. The earlier native-runtime evidence remains
separate. Continue shared outbox scope and all-company receive/recovery; backend
push delivery remains disabled pending real receive acceptance.

Local reference reading and reconciliation now also accept Captured scope; see
[room recovery and reference ownership](collaboration-room-recovery.md). They retain
local facts without borrowing an SDK runtime or replacing provider browse positions.
