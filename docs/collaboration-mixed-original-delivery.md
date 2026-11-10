# Mixed original message delivery

DeliverCollaborationSpaceOriginalMessages retains one exclusive physical queue lease and
one bounded invocation. It now handles original company commands and new Space commands
in their actual namespaces, including a newly published Space room with no company
command partition. It never authors or enqueues an intent, creates a client/key/room,
archives history or calls another usecase.

The invocation captures the complete paused SDK inventory and complete pending/journal
lists. Its optional CompanyLedger and mandatory SpaceLedger phases read exact active or
archived facts, validate every original and plan both projections before either can write.
Every physical original belongs to exactly one logical command; Sent requires immutable
journal/archive proof. Effective aliases, logical IDs and physical device/room/transaction
ownership are unique across both complete ledgers. A changed bootstrap/native session
never releases another unresolved Staging record on this physical device/room. The same
pure exclusion applies before new staging; exact recovery still requires the full device.

Each projected CAS is followed by exact readback, including false/failed responses.
Partially committed projections remain durable for a later invocation. Only exact confirmed
originals may be settled. The owning flow recaptures the complete remaining paused queue
before resume and rechecks the room binding. Recipient readiness permits new delivery;
retained receipts and disappeared original recovery remain separately guarded.

Native callbacks retain their immutable typed capture. The entire observation is validated
before any journal append, and existing factual aliases prevent cross-ledger reuse in a
later callback. Bounded noncancellable writes preserve a first committed journal if the
second write loses its response. Callback storage retains the original account scope;
current admission separately controls projection. Closing pauses and drains the owned
native child before releasing the writer, including cancellation and late callbacks.

For vanished TEXT originals, original-company local history is checked in its own scope.
One bounded original SDK window covers still-missing company and Space transactions.
Matching requires the exact sender, logical ID, original content hash and body. Missing or
mismatched history remains unresolved, never a synthesized acknowledgement or another send.
Edit callback-loss evidence and incoming Space history/retirement remain separate work.

Local tests cover mixed/new-room delivery, complete-ledger collisions, Staging barriers,
response loss, partial commits, exact readback, missing callbacks, bounded history,
late aliases, cancellation, denial, deadlines and writer release. Fixtures use a synthetic
queue. [Local evidence](measurements/collaboration-mixed-original-delivery-2026-11-10.json)
records actual checks and limitations. [Native acceptance](collaboration-mixed-space-native.md)
now proves one NEW fictional Space intent through real Queue/Stage beside the unchanged
Company originals, SDK-dequeue/app-receipt loss, exact recovery and offline restart.
Complete pending-command inventory and incoming routing remain required before activation.
Actual Apple Matrix runtime binding and all communications PRD acceptance remain open.
