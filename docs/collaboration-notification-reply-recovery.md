# Fair account discovery for retained notification replies

The reply recovery cursor is separate from the employee command store, company
selection and the reply input documents. One account/audience-owned encrypted
checkpoint beneath `collaboration-history/notification-reply-recovery` contains a
fresh generation UUID and the last attempted local creation sequence. The existing
history content key is required for every read/write; no operation creates or
migrates a key. Account, audience, partition, generation and revision are
authenticated, with bounded plaintext/ciphertext and exact-integer sequence bounds.

`ReadCollaborationNotificationReplyRecovery` captures the current native parent and
returns at most four ordered opaque input IDs with creation sequences. It validates
all loaded records before removing company text from the returned work. Held inputs
are excluded. Discovery may wrap once when nothing remains after the saved seek;
it never loops over the entire store. A retained company denial does not block
account-level ID discovery. Reading text or dispatching still requires the separate
company admission used by the reply workflow.

`AdvanceCollaborationNotificationReplyRecovery` advances the exact cursor before an
application caller attempts a selected input. Subsequent entries in a discovered
batch use the immutable original work plus the cursor returned by the prior advance.
The supplied expected cursor must be the discovery cursor or an earlier selected
entry. The stored generation must still match, so stale work cannot reset a newer
seek. A completed wrap may move the sequence backwards using the exact prior cursor.

Both use cases have eight-second bounds and check native parent ownership around
pending storage calls. Cancellation or account/family replacement after a commit
withholds acknowledgment while retaining the committed seek. A failed, denied or
slow reply can therefore be passed on the next discovery after process restoration.
The cursor grants no permission to expose content, queue a message or clear input.

This increment provides the encrypted cursor, discovery/advance use cases and data
module bindings. Application/global recovery wiring follows: reserve a bounded reply
phase without starving priority/company recovery, persist seek before each attempt,
continue to later pages past denied inputs, and require a later external/periodic
wake after a cycle ends with only denied input. Do not turn a post-page wrap into an
endless immediate retry loop. Native receivers and notification display remain off.

The worker must also retain failure state across pages and process restarts: a healthy
last page cannot acknowledge a wake while an earlier transient input still needs a
retry. Persist in-flight/sweep retry outcomes, or prove another bounded durable
strategy, before activating global wake completion. The seek alone does not provide
that completion policy. A multi-page transient-first/healthy-last/reopen regression
is required alongside denied-only cycle coverage.

## Durable cycle follow-through (planned, not activated)

Extend the cursor with a compatible optional cycle containing `initial`, `inFlight`,
`retryRequired` and `finished`. Keep legacy encrypted integer cursor decoding; the
new versioned structure uses the same existing key and authenticated owner/partition.

Begin retains an active cycle, or starts an absent/finished cycle from the minimum
sequence. Discovery in an active cycle never wraps at the tail. Advancing before an
attempt atomically records its sequence and `inFlight`; an interrupted prior attempt
sets the sticky retry flag. Settling an exact cursor clears `inFlight` and accumulates
transient failure/timeout/contention. Denied input stays retained but does not demand
immediate retry. Finish persists the retry flag, including any unsettled attempt,
and marks the cycle finished before returning its outcome.

After process death or a lost Finish result, the next Begin must start from the
minimum again. It must not inspect only newer tail inputs and acknowledge a wake
while an older transient reply remains. A completed denied-only cycle ends without
immediate continuation; an external/admitted/periodic wake starts a new full cycle.
A healthy later page cannot clear a prior retry flag. The bounded application phase
settles at most four inputs, with per-attempt bounds, and leaves the existing priority
and ordinary company turns reserved. Global wake completion combines all lanes;
a transient retry takes precedence over a healthy immediate continuation.

Required tests include transient-first/healthy-last across pages and reopen,
Advance/Settle/Finish interruption, new tail input after an interrupted Finish,
denied-only finite completion, and denied/slow first input with other-company progress.
The current standalone seek foundation does not implement these cycle transitions.
The supplementary application-worker draft with only ephemeral page outcomes is
incomplete and must be rewritten before activation.
