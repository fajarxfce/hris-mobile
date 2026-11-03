# Saved notification replies

Chat opens a company-scoped Saved replies list and a separate detail route. The
account-owned encrypted input is displayed with its recipient when the retained
conversation is available, its original text and a short explanation of a hold.
Waiting input remains distinct from a delivered message. Open chat navigates to
the existing conversation without modifying its composer or acknowledging a read.

The list observes the reply checkpoint separately from notification publication
sources. It scans at most four pages/64 owned inputs within eight seconds, validates
all records, and exposes only the selected company's admitted content. Local age
does not expire input. Session/workspace/denial changes cancel pending reads; source
completion is unavailable rather than a lasting authorization. The route owns the
ViewModel; stop clears private state and cancels work, while reentry joins the old
owners before starting new reads.

Discard requires an explicit confirmation of the displayed input. The use case
checks the selected company, current local/native session and exact input version.
For an absent message it writes a permanent encrypted cancellation directly to the
existing command archive. No Queued row is published. An existing matching Queued
message uses exact atomic cancellation; already started work or a different body
is preserved with an explicit result. The archived operation is read back and
authenticated before exact input removal. The original command ID remains consumed
across response loss, restart and a late duplicate callback. A removed input is not
proof that the message was discarded, so an already absent input has its own result.

The generic CommandStore.archiveAbsent operation checks session/account, existing
ID and occupied resource in the same transaction as archive creation. Existing
archives are immutable; caller encryption and policy remain in the feature. Room
and IndexedDB use the existing archive table, with no schema migration. A competing
queue insert or staging transition can win, in which case discard keeps the input.
The transaction and operation fence provide arbitration across independent app
owners; no in-memory mutex is treated as the durable cancellation decision.

This increment does not send edited review text or reauthorize an old notification
token. Those actions require a durable reviewed submission tied to a fresh operation,
with explicit recovery of uncertain submissions. A different-body operation conflict
stays reviewable; it is not silently cancelled. Production notification hosts remain
unbound and chat push remains off pending complete action/status/review acceptance.
Physical iPhone testing is waived; full hosted Apple build and simulator coverage
remain required. Generic archive creation still needs the proven Starter port.

Local acceptance is recorded in [the measurement](measurements/chat-saved-replies-2026-11-03.json):
3,898 desktop,230 Android host and34 actual browser cases, zero failures; root
quality/shared Wasm/Android assembly and lint passed. The three controlled Compose
screenshots were actually inspected, including Indonesian at twice the font scale.
Full hosted Apple and the next phone release remain pending. See the current agent
handoff for the next native action and reviewed-send work.
