# Immutable encrypted published-room bindings

The candidate `CollaborationSpaceRoomBinding` retains the complete canonical Space
conversation facts, published provider room and, for LEGACY, the complete original
conversation/room metadata. It contains no login, native parent, credentials, SDK
handle, current directory admission or mutable provisioning status.

Domain policy matches a LEGACY binding by the exact original company/conversation
position, canonical participant pair, server, room and microsecond binding time.
Original conversation and room creators are validated independently; they may differ
from each other and from the later Space conversation creator. The original may
predate the Space conversation. A selected HR company or Space ID is never used as
a fallback original coordinate. SPACE bindings cannot carry historical originals.
The existing remote metadata policies reuse these pure fact checks while retaining
their explicit current-owner and provisioning validation.

The repository coordinates the existing raw ReplicaStore, ContentCipher and atomic
credential store. The owned codec maps a bounded versioned envelope. The namespace
`collaboration-history/space-room-bindings/v1/<space>` belongs to the existing shared
history-key inventory. Retained bindings, including inactive-account records, prevent
silent recreation of a missing key. Existing history and canonical-metadata records
are not rewritten. No SDK store, message queue, enrollment or network operation runs.

Each binding has two HMAC indexes: conversation ID and the structured pair of provider
server/room IDs. Account, API audience and Space are authenticated in every index;
content also authenticates generation, row purpose and insertion revision. Both rows
publish atomically with one compare-and-set checkpoint. Identical retention preserves
ciphertext/revision; contradictory facts and one-sided indexes cannot overwrite the
original. Conflicts return to the owning caller without internal retry. Exact reads
use bounded published queries, validate both rows and recheck the final checkpoint.
They neither scan the partition nor impose a time-to-live on owned local facts.

Plaintext envelopes are bounded to8KiB and encoded envelopes to16KiB. Lookup queries
return at most two rows per index. Revisions remain within the JavaScript-safe range.
A missing exact index returns absence; this is not a full-partition corruption scan.
Canonical pair uniqueness is retained by the separate conversation-metadata repository;
the owning acquisition must match that metadata before it persists a room binding.

## Validation in progress

Fourteen common storage cases have actual desktop SQLite, browser IndexedDB and Apple
SQLite wrappers. They exercise both origins, keyless absence, reopen/login replacement,
exact LEGACY mismatches, immutable conflicts, bounded reads, competing publications,
lost responses, account changes, shared-key loss, corrupted rows/checkpoints and
cancellation. Apple execution is separate from source compilation. The initial local
focused gate82526 is still running in `.work/space-room-binding-2026-11-09/focused-1`.
No acceptance, new APK or product activation is claimed yet.

## Owning flow follows

The next use case must recover an existing local binding independently of credential
age, then separately coordinate current native/directory admission for remote acquisition.
It must read original metadata by the published exact position, validate every response
against its captured owner, match canonical metadata, retain once, and read back the same
immutable relation after an uncertain write. Retained facts cannot reopen a denied
content projection or authorize SDK adoption, message sending or key replacement.
Original SDK delivery/history, background workers and product routing follow separately.
