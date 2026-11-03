# Reviewed notification replies

[Local acceptance](measurements/chat-reviewed-send-2026-11-03.json) records root,
Android/Wasm, encrypted SQLite/IndexedDB and inspected Compose UI checks. Hosted
Apple/native and phone release gates are tracked separately in the
[current handoff](agent-handoff.md). Phone24 predates this change.

## Working draft and explicit confirmation

A held notification reply may contain an encrypted working draft, including an
intentionally empty draft. Its authenticated original candidate, token, operation
ID, document ID and sequence remain unchanged. Autosave does not approve a send or
cancel another command. The UI shows saving/failure until the exact durable copy
has been reproduced. Completed saves survive route disposal and process restart.
Leaving while a write is pending or unconfirmed requires a separate choice.

Sending explicitly persists an immutable resolution: a fresh operation ID,
confirmed body, millisecond creation time and the actual four-part native parent.
The original and edited operation IDs differ. A lost response or retry reuses the
same ID/body/time. Once confirmed, this body is read-only; another native session
family needs explicit reapproval and changes only the approval parent. Recovery
cannot silently approve it for the new family.

The page receives the actual observed parent, not a parent reconstructed from
local account access. Each finite use case checks the current session, native
family, selected/captured company and admission before and after repository I/O.
The ViewModel cancels and joins obsolete work and clears its private state when
its observed owner changes or the route stops. Autosave successors join the full
predecessor chain, including a cancelled intermediate write.

## Queue and recovery ordering

The account reply coordinator routes confirmed resolutions before the original
notification completion path. An existing matching edited command is reproduced
before retiring an exact input version, including after same-account reauthentication.
Original cancellation alone can never prove completion of an edited resolution.
A duplicate native callback also cannot retire an unconfirmed working draft,
including an empty draft, merely because the original command has finished.

For a new edited command:

1. Reproduce the exact retained confirmed input and current consent parent.
2. Require an owned conversation under current company admission.
3. Permanently archive an absent original operation as Cancelled, or atomically
   cancel its exact Queued command. Never insert a briefly deliverable original.
4. Reproduce that permanent cancellation, then recheck the exact confirmed input.
5. Insert the edited operation once and reproduce its immutable body/owner/ID.
6. Retire the exact input and request a finite delivery wake. Failed wake does not
   undo an existing durable command; ordinary recovery can discover it.

A started/staged/sent original holds the edit as OriginalProcessing. A conflicting
body holds it as MessageChanged. Neither permits another automatic send. Storage
uncertainty, queue capacity, cancellation and admission loss preserve retained
state for retry. The finite resume budget is eight seconds; it performs no HTTP,
credential acquisition or Matrix SDK calls.

## Discard and races

Normal discard permanently fences both original and confirmed edited operation
IDs before removing the exact input. An already-started command or a conflicting
body is preserved. The new operation's permanent archive also arbitrates a late
resume that passed its final input read before discard won the race.

When an original has already started, the page offers a separate explicit
“Discard saved edit” action. It preserves the original and fences only the
confirmed edited operation before removing input. If that edited operation has
also started, it cannot claim cancellation. Conflicting operation bodies are
never overwritten, cancelled or relabelled as a successful discard.

## Storage compatibility

Reply document schema2 adds optional draft/resolution fields to the existing
account-owned AES-GCM storage. Index identity, associated-data namespace and
checkpoint schema remain unchanged. Schema1 ciphertext is still readable; no
Room/IndexedDB schema migration is introduced. A document contains at most the
original plus one working/confirmed body. Confirmation clears the working draft.

Each body stays bounded by 16,384 UTF-16 units/32,768 UTF-8 bytes. Drafts allow empty
text; confirmed bodies use normal nonblank message validation. Encoded input is
bounded by 131,072 bytes and plaintext by 98,200 bytes, inside existing generic
replica limits. No generic storage limit is widened. Malformed or oversized
inputs fail without truncation or replacing existing ciphertext. Confirmation and
draft edits use exact checkpoint/document CAS. Duplicate/resealed callbacks keep
the existing working draft or confirmed resolution.

## Acceptance scope

Require meaningful domain interruption/reapproval/fence tests, actual encrypted
SQLite and IndexedDB reopen/CAS/legacy/bounds checks, actual-Koin app recovery,
MVI cancellation/typing/private-state checks, keyboard and compact EN/ID layout
inspection, root quality and Android/Wasm compilation. Full hosted Xcode remains
required; physical iPhone testing is waived. Android minified release smoke and
install-only verification are separate. This does not enable live chat push or
claim completion of calls, video, meetings, E2EE backup or the entire chat PRD.
