# Retained notification reply workflow

`CollaborationNotificationReplies` is the shared application entry point for a
native reply submission or an already-retained input ID. The shared app container
owns one instance. It coordinates use cases; platform callbacks, storage adapters
and feature use cases do not call each other.

Submission authenticates and retains the exact reply token and text before any
company send admission. It then requests recovery with a one-second bound; a failed
request does not undo retention. Startup/periodic discovery is still needed to pick
up retained input when scheduling or the process is interrupted.

Each invocation has an eight-second total budget. Up to eight submissions, including
the active one, may wait for the serialized turn; waiting consumes the same budget.
A ninth submission fails explicitly. Cancellation releases both turn and admission
slot. Recovery uses the same turn and declines contention without adding a waiter.
Nothing owns a detached coroutine, Activity or native callback. The platform caller
must keep its own bounded lifetime, join cancellation and handle failure before
retention. In-memory waiters are not an OS process-death guarantee.

Confirmed [reviewed submissions](collaboration-reviewed-replies.md) enter their
separate finite resume path before original completion. They retain one fresh ID
across retry/reapproval and fence the original before queuing the confirmed body.
A working draft cannot be erased by a duplicate original callback.

For untouched notification input, the workflow first reproduces a matching immutable
command through exact completion.
It can retire input for an already-retained command even after same-account
reauthentication. If no command exists, held input stays held. Review reproduces a
permanent conflict or missing context; a transient storage/transport failure never
becomes a permanent hold. A null review is not permission to send.

Before dispatch, the workflow rereads the exact retained version and invokes the
authenticated command path using the original token and text. That path rechecks
current family, company and conversation admission. The workflow then reproduces
the command again, even after an ordinary failed dispatch: a write may have committed
before an error was observed. It never sends again merely to resolve uncertainty and
never retires input solely because dispatch returned success. Cancellation propagates;
input and any committed command remain available for later recovery.

Outcomes have distinct meanings:

- `CommandRetained(state)`: exact command proof preceded input retirement. The state
  can be queued, sent or an authenticated cancelled archive; it is not a delivery claim.
- `NeedsReview(inputId)`: input remains encrypted with its original text and token.
- `Absent`: no retained input exists at the inspected point; message existence and
  delivery remain unproven.
- Failure: no success is acknowledged. Input or a command may already be retained.

Results are not notification display/cancellation leases. Native display ownership,
authenticated action issuance, retained input discovery with a fair durable account
cursor, explicit review UI and platform callback wiring have separate contracts and
acceptance records.
Chat push stays disabled until the complete owned native flow is usable and verified.

Validation: [actual checks and limits](measurements/chat-notification-reply-flow-2026-11-02.json).
