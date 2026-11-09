# Bounded local organization room inventory

Recovery can enumerate retained room bindings without querying a remote conversation
list. CollaborationSpaceRoomBindingInventoryRepository is a separate read contract
implemented by the existing encrypted binding repository, sharing its raw ReplicaStore,
content-key owner and immutable two-index namespace. No binding is rewritten and no
new schema, provider credential or key is created by a read.

Each page examines at most 50 raw entries plus one lookahead. Raw index order is
opaque HMAC order, not conversation order or insertion time. Every examined row is
authenticated, and every processed binding is verified against both immutable indexes
and their original insertion revision. Only its conversation index emits a binding.
A page containing only room indexes can therefore return no bindings and still have
a next cursor. Lookahead makes the last exactly-full page terminal without requesting
another empty page.

The first page captures account, audience, organization, generation and revision.
Continuation preserves those coordinates across a same-account login replacement;
the current login still supplies storage ownership. New rows appended after that
revision are authenticated but excluded from the captured inventory. A separate flag
reports newer bindings, including appends that sort behind the continuation cursor.
The owning recovery flow must arrange a fresh sweep; this page does not schedule it.

A changed generation, incomplete/tampered pair, invalid cursor, lost key or account
replacement during I/O fails without repairing data or automatically restarting the
scan. The final checkpoint is checked again after decryption. Cancellation propagates.
An absent first namespace is a keyless empty local result, not proof of live access;
the owning domain flow must guard its current parent around the read.

Nine added cases share the existing actual SQLite/IndexedDB/Apple Room contract:
keyless and invalid input, room-only continuation and unique output, process reopen,
exactly-full exhaustion, appends before/after the seek, replaced namespace, corrupt
rows/pairs and lost keys, cancellation/account replacement, and replacement during
final checkpoint decryption. A fixed fictional test key makes the append ordering
reproducible; original production key generation is unchanged. Application graph tests
verify that read/write interfaces share the same repository instance.

Focused source validation passes 25 cases in 208.1 seconds, including the 14 existing
binding cases, nine new inventory cases and two application graph cases. Required
full validation passes 4,328 fresh cases in 575.815 seconds: 3,679 desktop, 626 Android
host and 23 forced browser cases, zero failures/errors/skips. Root check, Android
release compilation/lint, shared Wasm/iOS source and quality checks pass. Both gates
retain identical 4,595 source inputs and unchanged Inspector bytes; every current
source, library input and XML report/count is verified. Apple execution of this
successor remains separate from the accepted earlier protocol CI.
[Retained evidence](measurements/collaboration-space-room-inventory-2026-11-09.json).
The reader does not deliver messages, persist worker progress or activate product
routing. New commands in an existing room need independent durable work signals;
room-binding revision alone is not a message-arrival indicator.
