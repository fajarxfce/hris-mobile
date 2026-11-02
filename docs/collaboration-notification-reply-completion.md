# Completing retained notification reply input

`CompleteCollaborationNotificationReply` removes an exact input version only after
reproducing the matching immutable message from encrypted command storage. The
account/audience/company, conversation, operation ID and exact text must match, with
a valid command revision/state. Queued, staging, staged, sent and cancelled commands
all preserve the user's original input. Retirement describes local retention, not
provider delivery or successful sending.

The use case captures the current native session and checks company admission before
and after pending reads and removal. It verifies the acquired direct conversation,
but does not require the original notification message to remain in history. It does
not refresh credentials, select a workspace, enqueue a message, open an action token
or schedule transport. Same-account reauthentication can recognize an old retained
command without making its old notification token a new send grant. Pending operations
stay pinned to the current family; replacement accounts cannot read or retire input.

Input retirement uses the exact loaded value and revision. An older callback cannot
remove a newly reviewed or re-created input under the same keyed ID. Changed reply
text on the original operation remains retained. Missing commands return `Pending`;
missing inputs return `Absent`, which proves neither storage of a command nor delivery.
A removed input whose acknowledgment was lost can therefore return `Absent` on retry.
The durable message remains the authority. A duplicate native callback can retain its
input again and reproduce that exact command; it must not create a new operation.

Storage failure, missing conversation, denial, contention or uncertain completion do
not become permanent review decisions. A cancellation or owner loss after a completed
removal withholds the acknowledgment while the matching command still retains the
text. Execution has a ten-second budget, joins cancellation and rejects overlapping
completion attempts without accumulating a waiting queue.

Application/native owners must still coordinate input retention, exact-token dispatch,
completion, bounded recovery and explicit review. This use case grants no native UI
publication/cancellation lease and is not an Android receiver. Display ownership,
notification actions and recovery UI remain separate integration work. No push flag
is enabled by this change.

Required follow-through:

- The application reply owner retains input, attempts completion first, and dispatches
  only a still-pending, unreviewed input using its exact stored token/text. It repeats
  completion after dispatch. Uncertain outcomes retain their input and operation.
- A review use case reproduces immutable conflicts, original-family replacement or
  missing acquired context before holding input. A matching command needs completion;
  malformed or cross-owner records and transient failures do not justify permanent
  review. Every hold uses the exact retained version and checks ownership afterward.
- UI reads by opaque input ID require current account and company admission before
  exposing text. Missing conversation context must not silently erase private input.
- Recovery uses bounded pages and a durable fair account cursor outside the employee
  command queue. Advancing after a denied/slow item prevents it from starving later
  inputs across process restarts. A saved old token never gains new-family authority.
- The native publication owner persists issued tokens and exact card/render state,
  serializes display/cancellation, and rotates the reply operation after accepted
  input. It preserves uncertain OS outcomes and never cancels an unrelated card when
  another candidate is suppressed. Enable real callbacks only after this flow exists.
