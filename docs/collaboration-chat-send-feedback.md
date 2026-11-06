# Message submission feedback and order

An ordinary local submission used the same `submissionPending` flag as recovery
from an interrupted save. The page now shows the recovery message only when that
flag remains set after the active save ends. Retrying hides it while work is
active, and an unresolved retry restores it. The draft and durable command remain
owned by their existing use cases and repositories.

Pending outgoing rows follow confirmed history and retain their local creation
order. This prevents a phone clock behind the server from placing a later pending
message above an earlier confirmed message. Stable row keys, actual timestamps
and delivery evidence remain unchanged; a check mark still requires confirmation.
The regression covers sequential acknowledgement of AAA, BBB, CCC and DDD, including
retirement of confirmed commands and both clock-skew directions.

Two closed worker diagnostic stages distinguish staging a native transaction from
resuming its delivery. No identifiers, message content or free failure fields are
logged. No retry, deadline, ownership or queue-admission policy changes are included.
The previously observed long pending duration is still under investigation.

Focused corrected verification:16 projection/Compose UI cases pass. Full checks
cover6,983 cases across two disjoint reports:6,775 passed before Wasm compiler heap
exhaustion; unchanged sources with2560MiB heap then passed the remaining208. Android
assembly/host checks, proof compilation, browser tests, lint0, formatting and
architecture checks pass. [Evidence](measurements/collaboration-chat-send-feedback-2026-11-06.json).
Release43 build and device acceptance follow separately; see the current handoff.

## Pending-delivery investigation

Old phone diagnostics identify `Flush / QueueUnresolved`; they do not distinguish
staging from delivery. Build43 adds that boundary without changing queue policy.
ADB was disconnected at08:52UTC. An earlier empty `pidof` response did not establish
that the app had stopped; no new diagnostic attempt or phone UI action was induced.

The existing worker fixture's lost-response case removes native queued items before
returning its simulated failure. It does not reproduce a server-accepted event
whose transaction remains in the protected SDK queue after process death. The
worker can recover confirmation from original history, select no remaining pending
commands, then retire the confirmed command. The added fake-SDK regression reproduces exactly that: first-message recovery
completes, then the next delivery is blocked by `QueueUnresolved`. Both expected
failure runs and the isolated patch are [recorded](measurements/collaboration-confirmed-queue-reproduction-2026-11-06.json).
This still does not establish the phone's current root cause.

Do not blindly abort or replay such transactions. In the pinned SDK,
`SendHandle.abort_with_reason` can schedule a server redaction when a send is in
flight. Queue cleanup needs exact original evidence and a native operation that
cannot delete a delivered message. Account/store/device fences remain required.

Read-only Synapse log sample on6 November08:00–08:55UTC:13 encrypted-send
requests all returned200, median server processing36ms, maximum55ms. Thirteen
key queries returned200 (median2ms/max4ms);150 sync requests returned200 with
about10s long polls. These are server request durations across the existing private
runtime, not phone end-to-end timing or proof of a particular pending transaction.
No actor, room, payload, token or request URL is recorded in the evidence.
