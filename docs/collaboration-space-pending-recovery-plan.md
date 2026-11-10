# Pending Space recovery — implementation handoff

This is the next increment, not accepted worker behavior. The existing application worker
still scans retained room bindings. Complete pending inventory is accepted in b03f591d;
mixed/new-room demand is now locally accepted (9,932 verified cases). Source acceptance and phone/live activation
are separate. All54 communications PRD groups remain open.

## Preserve durable work during upgrade

Keep the current encrypted space-recovery/v1 partition, authenticated scope/native family
and generation-bound CAS. Existing schema1 pending binding claims must resume before fresh
command discovery. Add a separate nullable message phase: original creation Instant plus
logical ID cursor, one explicit message/conversation claim, and explicit end-of-scan.
A null phase means unscanned legacy state; it never proves the command partition is empty.

Use schema1 when the extra phase is absent and schema2 when present, with strict decoding
of both and rejection of unknown versions or inconsistent fields. Preserve the4096-byte
plaintext bound. Persist only the claimed immutable identities, not an entire128-row list.
Serialize exact canonical Instant including fractional precision; rounding to milliseconds
can reorder messages. Test same-millisecond/different-nanosecond originals.

## Owning sequence

The application worker composes existing usecases; usecases never call each other. Read
complete pending inventory, then persist one claim selected by a pure policy before SDK work.
Resolve its exact current command and canonical local conversation under current admission.
A missing or terminal row ends only that attempt, never confirms a message. Missing canonical
metadata must not be invented from the outgoing command.

Prepare the room binding, stage Queued or uncertain Staging through the owning staging
usecase, then deliver originals through the existing mixed writer. Each usecase retains its
live permission, directory, current native-family and physical-writer checks. Confirm the
claimed ID from delivery results; unrelated queued messages must get their own later turn.
Do not create a replacement message, SDK transaction, key or native identity to unblock work.

Preserve an old pending binding first, then unfinished message work, then remaining binding
pages. Advance the failed message's original cursor durably before scheduler backoff so one
unready recipient cannot monopolize later turns. Keep at most four actual attempts per turn;
a one-time empty message phase can proceed to a binding attempt in the same unit. Account
replacement or confirmed admission denial still stops current work.

## Completion and failure

The dedicated Finish owner must re-read the complete local message partition before returning
any completed wake, including for schema1 progress. Pending commands restart a finite sweep;
producer writes after the final read retain a newer atomic wake generation. Completion can
only acknowledge the originally captured generation. Failed or unready work uses scheduler
backoff; healthy continuation is not an immediate retry loop.

Review the generic UpdateProgress public Finish path: currently it can finish without the
separate Finish owner's inventory read. Prefer rejecting that operation in the generic
updater and routing completion through the dedicated owner. Preserve prior test identities
while exercising the stronger completion owner; add an explicit bypass-rejection case.

## Required acceptance

Cover schema1 pending-claim upgrade and schema2 exact roundtrip, authenticated field/scope
changes, unknown schema, size bounds, CAS and response loss. Exercise full128-row discovery,
invalid terminal rows, commands without bindings, ordering at submillisecond precision,
failed-first/sendable-second fairness, claim/process/staging/ACK interruption and recovery.
Verify owner/denial changes and cancellation at every new storage boundary, empty/unavailable
flows, late command appends on both sides of the cursor, and exact wake completion.

Retain existing original-only and actual group-worker native controls. Add a real native
pending Space worker case; the accepted mixed native proof invokes delivery directly and
uses an adopted legacy room, so it does not already establish this behavior. Android phone
is install-only. Apple shared tests and unsigned host do not implement the missing native
Matrix runtime. Incoming/history/edit routing and the larger media/backup/call/meeting work
remain separate.

Private exploratory drafts currently live under .work/mixed-recovery-demand-2026-11-10/
worker-draft, but this tracked plan is sufficient to reconstruct the intended boundaries.
Draft code is unapplied and untested; review it before adoption, especially progress policy
method size, completion bypass handling, DI and old fixture constructor updates.
