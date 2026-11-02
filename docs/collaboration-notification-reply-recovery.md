# Durable recovery for retained notification replies

The reply recovery cursor is separate from the employee command store, company
selection and reply documents. One account/audience-owned encrypted checkpoint
beneath `collaboration-history/notification-reply-recovery` contains a fresh
checkpoint generation, the last attempted creation sequence and an optional cycle.
Every read/write uses the existing history content key (`create=false`). No new
key, table or schema migration is required. Account, audience, partition, generation
and revision are authenticated; plaintext/ciphertext and exact integer sequences
are bounded.

Legacy encrypted integer cursors remain readable. A cycle uses the versioned v2
payload with a cycle UUID and `initial`, `inFlight`, `retryRequired`, `finished` flags.
It retains the v1 authenticated-data format for backwards reading. Invalid flag
combinations, unknown fields and malformed generations are rejected. Legacy raw
advance refuses to replace an active or finished cycle. Every replacement compares
the exact cursor and creates a new checkpoint generation. A stale settle cannot
clear an accumulated retry; work discovered for an older cycle cannot advance a
newer cycle even at the same sequence.

## Beginning and discovering a cycle

`BeginCollaborationNotificationReplyRecoveryCycle` captures the native parent and
keeps an interrupted active cycle. For an absent, legacy or finished cycle it
validates a bounded pending-input probe before starting from the minimum sequence.
An empty queue returns no work without creating a cursor or requesting a history
key. This also works for a newly signed-in account with no prior history key.

`ReadCollaborationNotificationReplyRecovery` returns at most four ordered opaque
input IDs and creation sequences. It validates loaded records and excludes held
inputs; returned work contains no company text or send grant. A company denial
does not gate this account-level discovery. Active cycles never wrap at the tail.
The standalone legacy cursor path retains its one-wrap compatibility behavior.

## Attempt, settle and finish

`AdvanceCollaborationNotificationReplyRecovery` writes the selected sequence and
`inFlight` BEFORE the caller attempts a reply. The batch remains immutable; later
entries use the exact cursor returned by the previous transition. An interrupted
prior attempt sets sticky `retryRequired` before progressing to a later input.

`SettleCollaborationNotificationReplyRecoveryCycle` compares the exact in-flight
cursor, clears `inFlight` and accumulates the caller's retry result. It cannot
clear an earlier retry. Confirmed company denials leave input retained for a later
admitted wake; transient failures, timeouts and contention require retry. This
metadata transition itself never sends, deletes input or marks it for review.

`FinishCollaborationNotificationReplyRecoveryCycle` rereads the exact cursor and
proves the pending tail is empty before committing `finished`. Any unsettled
attempt is included in the retry outcome. New tail input or a concurrent cursor
replacement prevents completion. After a lost Finish result or process replacement,
the next Begin starts at the minimum again, so a newly appended healthy tail cannot
hide an older failed reply. A healthy later page cannot clear an earlier failure.

Each use case has an eight-second bound, propagates cancellation and checks native
parent ownership after pending storage. Late account/family changes withhold success
while preserving committed state. Retained local ownership and current send
admission remain separate. Reopening storage does not grant a token or a send.

## Integration still required

These domain transitions, encrypted SQLite/IndexedDB storage and assembled data
bindings are implemented. Actual application/global recovery wiring is next:
reserve a bounded independent reply phase; advance before each attempt; settle its
result; finish the durable cycle; combine wake completion with priority and ordinary
company recovery. Any failure/backoff must take precedence over a healthy immediate
continuation. Denied-only completed cycles must not become an immediate retry loop.

The previous application-worker draft with only ephemeral page outcomes is obsolete.
Its healthy last page could forget an earlier transient input. The replacement must
use Begin/Advance/Settle/Finish and include actual worker budget, cancellation,
other-company progress and wake acknowledgment tests before activation.

Native display journal/issuer/callbacks, notification reply review UI and OS-death
reply-input acceptance remain outstanding. Chat push remains off. The existing
native interop scenarios cover other recovery paths, not this input lifecycle.

Validation: [original seek](measurements/chat-notification-reply-recovery-2026-11-02.json)
and [durable cycle](measurements/chat-notification-reply-cycle-2026-11-02.json).
