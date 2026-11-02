# Retained local read state

Read state stores exact acquired event IDs and a bounded queue of receipt intents
in the account/company/conversation partition. It does not infer coverage from
message timestamps, read an unseen history gap or treat a server receipt as a
recipient's durable delivery ACK. Local read/mark use cases admit the current account and retained content gate.
Marking validates each selected original or confirmed tombstone before storage.

A mark atomically adds one to128 exact read facts and appends its immutable intent.
The observed generation/revision must still match. Other read facts and pending
intents remain retained; an older point can coexist with a newer point until the
owning delivery operation processes them. A full128-intent queue rejects the entire
new mutation without silently evicting anything. Repeating an active or last
acknowledged intent does not duplicate it, and reusing that known ID with different
content is rejected. A receipt acknowledgement removes only the identical queued
intent and retains its outcome in the same commit, leaving concurrent/newer intents
untouched. The caller owns scheduling, ordering, retry and any future compaction.

Reads select at most128 distinct event IDs and verify one consistent checkpoint
around their bounded indexed acquisition. Empty selections read only queue metadata.
No complete conversation scan is needed. Ordinary age and expired credentials do
not delete this store. Account activation and the storage transaction fence late
writes; company/room context also authenticates every value.

The separate `collaboration-history/read/` namespace uses the existing protected
history content/index key. Purpose-specific authenticated encryption and keyed
indexes separate this format from original messages and traversal records. The
existing key-absence guard includes this namespace and all inactive accounts, so
losing a key cannot create a replacement over retained read records. No new host
credential registration or database migration is required. Plaintext is bounded at
64,000 bytes and ciphertext at96,000 characters. Event IDs, room IDs and intent IDs
never appear in document indexes or unencrypted payloads. Generation/revision,
account/company/conversation/room, document identity and format purpose authenticate
the ciphertext; all selected tags and sort keys are checked against their decoded
content. Revision exhaustion refuses another write while retaining readable facts.

The initial replaceable-intent draft passed nine SQLite cases and two actual shared
container checks. Review then changed the contract to retain a queue: replacing an
unsent point requires an ordering proof that a timestamp cannot supply. The expanded
suite also covers capacity, exact replay and conflicting intent IDs. [Evidence](measurements/chat-read-state-2026-11-02.json): full root and11 IndexedDB
cases pass161.148s, with3273 desktop/230 Android host/10 build-logic cases and explicit
reused-task statuses. The eleven SQLite cases pass, including the full128-intent
queue, exact replay and conflicting IDs. Fresh accepted reports contain1373 cases
in156 suites. Earlier full check247.689s failed an existing transport fixture's
unsynchronized cancellation flag after the eleven storage cases had passed. The
fixture now awaits its own transport completion signal with a5-second bound and
asserts no result publication. Five browser transport cases independently pass
25.118s. No production HTTP behavior changed.

`ReadCollaborationReadState` returns a finite detached projection for a selected
workspace or captured account/company scope. `MarkCollaborationMessagesRead` verifies
the owned direct-conversation pair and every selected original/tombstone, then uses
the observed checkpoint for one atomic mutation. It never treats an unseen gap,
canonical timestamp or an outgoing local echo as acquired read coverage. Empty
queries may inspect metadata, while an explicit mark requires1..128 distinct events
and an intent whose target is in that selection. Reuse of an active/last-acknowledged
intent recovers a lost response; a changed known intent rejects. Both operations
check local ownership and retained denial after pending work. Ordinary credential
age does not prevent owned offline use; a replacement account/session cannot receive
late results. Optional expected-access binding rejects stale notification actions.

The shared mark instance rejects concurrent work instead of queuing it and gives
one operation10seconds. Cancellation joins suspended work and releases exclusion.
A failed/late response preserves an already committed intent. Marking never calls
the provider, enables sync, removes an intent, acknowledges server delivery or
requests a scheduler. Queue capacity and checkpoint conflicts require explicit
recovery; there is no internal retry or timestamp coalescing.

[Local policy evidence](measurements/chat-read-state-policy-2026-11-02.json):14 domain
cases and two assembled-container cases pass128.050s; full root plus14 browser cases
pass270.921s, with3287 desktop/230 Android host/10 build-logic cases including explicit
reuse.1716 cases in220 suites execute freshly with zero failures/errors/skips. The
first42.074s attempt stopped at a test assertion's incompatible generic expected
type; no tests ran, and production source was unchanged by that correction.

[Common runtime entry and admitted replay](collaboration-read-receipts.md) now
consume this queue and are proven through actual HRIS/Synapse/Android process-death
recovery. Worker/foreground invocation, notification suppression/actions, mute/privacy
and multi-device reconciliation remain. No live chat push or whole communications
feature is accepted here.
