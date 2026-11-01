# Owned offline text intents

`QueueCollaborationText` saves an explicit send action in the encrypted command
store. Its caller creates one logical UUID for the submission and retains it until
the result is known. Retrying that UUID returns the original text and timestamp;
different content cannot replace it. Whitespace and multiline content are preserved.
Preparation requires an owned, acquired direct conversation and the existing
company read-admission gate. It does not need a fresh login, enroll a device, join
a room, or send a request. Delivery will require separate live admission.

`ObserveCollaborationMessages` reads retained intents for the selected conversation.
Ordinary authorization age does not hide those facts. Account/workspace replacement
and confirmed company denial stop observation. A denial that arrives while a local
write is pending prevents publication while leaving the committed intent intact.
Only the existing admitted acquisition can reopen the company gate; message
composition never clears it.

## Storage and state

The existing account command store owns the transactions and local-session fence.
One company partition holds immutable request envelopes and independently encrypted
state envelopes. AAD binds API audience, account, company, logical UUID, envelope
purpose and state revision. Authenticated request content also binds conversation,
text, timestamp and sort position. The OS-protected command content key is shared
with existing commands; missing keys over retained ciphertext cannot be regenerated.
Reauthentication can reopen owned intents without retargeting their native attempts.

The existing account-wide capacity is 128 retained commands, shared with other
workflows. This is an explicit initial bound, not a production chat-capacity claim.
Each text is limited to 16,384 UTF-16 units and 32,768 UTF-8 bytes, with valid Unicode
and no control characters beyond newline, carriage return and tab. State snapshots
contain at most 128 native transaction IDs, each at most 128 ASCII token characters.
The final encrypted/base64 request and state fit the database's 128/32 KiB limits.
Reads decrypt at most the shared 128-record capacity; they never trigger delivery.

States advance through `Queued`, `Staging`, `Staged` and `Sent` by exact-record CAS.
`Staging` retains the original native session, communication device, bootstrap
operation, room and complete paused queue IDs **before** enqueue. `Staged` binds the
logical message to its native transaction. `Sent` retains that transaction and its
server event. No transition discards uncertainty, changes device/room, invents a
replacement transaction or resets a sent message. Retirement requires a future
durable message projection; there is deliberately no removal operation yet.

## Native transaction recovery

The pinned Matrix Rust FFI explicitly returns no `latest_json` for unsent local
echoes. Waiting for timeline hydration cannot recover their application logical
IDs. The earlier hydration proposal in the timeline research is superseded.
Per-room send-queue subscription does replay its captured transaction IDs before
returning. Under one exclusive enqueue owner with delivery paused, the difference
between a durable pre-enqueue snapshot and a complete current snapshot identifies
the original transaction. An older, already mapped in-flight send may disappear
without changing this result.

The pure recovery policy returns `Found`, `Missing`, `Ambiguous` or
`DifferentDevice`. `Missing` is meaningful only after draining any prior native
enqueue, on the same protected store, with a complete paused snapshot and no other
writer. A quiet or empty timeline is insufficient. Multiple new IDs and different
device families remain unresolved. A native operation must stay owned after caller
cancellation so a late SDK commit cannot race the next snapshot.

## Original payload identity

New staging prepares the exact plaintext payload before enqueue and persists its
trusted Matrix sender and full canonical content hash beside the native attempt.
The paused writer checks that identity again before mutating the SDK queue. Staging,
Staged and Sent preserve it immutably. Existing envelopes without an origin still
open, but an uncertain legacy attempt is never assigned a newly inferred identity.
See [ACK recovery](collaboration-original-receipts.md) for the precise evidence
required after process death and the literal text content contract.

## Exclusive native queue ownership

`CollaborationRuntimeRepository.acquireMessageQueue` now acquires a paused child
writer for a known encrypted room. All rooms on one protected runtime share one
exclusive writer. It does not join, bootstrap, refresh product data or enable
SDK delivery. Unsupported hosts explicitly report unavailable. The retained
native device/family/bootstrap/room binding is exposed through a domain handle;
the raw Matrix client remains in Android data.

`SdkQueueOperations` owns at most one native operation and no waiting backlog.
Snapshot/enqueue waiters are bounded to five seconds; the delivery waiter allows
25 seconds for its bounded window and listener cleanup. A waiter can time out or
be cancelled while the operation remains owned by the runtime. A competing snapshot/enqueue reports pending until the
operation finishes. Child shutdown stops new operations, drains the active one,
stops any captured listener, closes timeline/room handles and only then releases
exclusivity. Failed or slow cleanup remains owned through the existing parent
child registry; it cannot unlock the SDK database early.

A per-room snapshot owns one FFI listener, validates at most 128 transaction IDs,
and stops/joins its task before returning. Overflow or malformed IDs fail the
whole snapshot. It never infers absence from an unhydrated timeline. Enqueue
keeps the original logical ID in encrypted message content and leaves delivery
paused. A native lease is a primitive, not permission to discard app uncertainty.
The common staging use case now validates live HRIS admission, reconciles the
uncertain attempt for its room and commits its native mapping. Delivery still
requires a separate admitted lifetime.

The expanded fictional HRIS fixture deliberately retains app `Staging` across
real process death after native enqueue. On restart it checks the exact original
intent, derives the native transaction from the captured snapshot, stores `Staged`
and only then resumes the test exchange. This fixture separately checks writer
exclusivity, closed-handle refusal, re-acquisition and the final `Sent` mapping.
The [native-writer measurement](measurements/collaboration-native-writer-2026-11-01.json) verifies this actual process-death exchange in 39.509s, with all proof and cleanup flags true. Per-room delivery, durable history, cancellation UI and all chat routes remain pending;
these native primitives do not accept any complete PRD group.


## Admitted common staging

`StageCollaborationText` binds one immutable local intent to its original native
transaction. The application binding is a singleton with one serialized staging
attempt. It checks the live native owner, company gate, active platform device,
owned direct conversation and ready room before acquiring the paused writer.
Every I/O result is followed by gate and owner validation. The owner is captured
**after** the gate read, so an account switch during that read cannot publish
success or authorize enqueue. Received company denial is retained even if the
subsequent admission read fails; a recipient-specific denial stays scoped to that
operation.

Before enqueue it reads the bounded owned conversation intents and the complete
paused native snapshot. Every existing native transaction must have an original
mapping, and no sibling intent may remain uncertain. It CAS-persists `Staging`
before native I/O and `Staged` only after identifying exactly one transaction.
Failed preparation cannot enqueue. A lost response, failed post-enqueue snapshot,
failed mapping write or caller cancellation leaves the original attempt intact
for explicit recovery. Unknown transactions, ambiguous snapshots and another
native family cannot produce a replacement send. Already mapped/sent results are
idempotent reads of the original outcome; they do not authorize delivery from a
new device. There is no automatic retry or queue enablement in this use case.

The writer remains a child of the protected runtime after cancellation. If the
native operation itself is interrupted, even after its caller disappears, the
runtime is synchronously marked unavailable and retired through full child/task/
database-pool drain. It cannot admit another enqueue against a store whose late
commit is uncertain. Normal caller cancellation and waiter timeout keep the
native operation alive instead.

[Staging evidence](measurements/collaboration-message-staging-2026-11-01.json)
records 16 new common cases, including two account-switch regressions first
executed against the faulty ordering. The actual emulator exchange separately
rechecks the native storage/process-death primitive with the new runtime changes;
it does not claim to exercise the common use case through production UI.


## Per-room native initialization

The parent runtime keeps its default send queue paused. A new writer initializes
`Room.timeline()` through async FFI before invoking synchronous room controls:
first access to the pinned Rust room queue can spawn tasks. This order also works
for a room that has never had an application timeline observer.

The [two-room proof](measurements/collaboration-room-queue-2026-11-01.json) stages a
second room before opening the main timeline, kills the real process, restores
both original transactions and enables only the admitted room. After both encrypted
directions complete, the other room is still disabled with its original queued
transaction; the server sees no send attempt for it. The fixture uses per-room
controls and pauses the admitted room on completion. Product queue leases now also expose a bounded delivery window. The common
live-admitted sender and durable recipient/history reconciliation still need to
connect that primitive to product UI.


## Owned delivery windows

`CollaborationMessageQueueLease.resume` consumes an immutable set of original
mapped transaction IDs. Its application caller must have current HRIS admission
and durable intent mappings for the original device/family/room. It captures the
per-room queue before enablement; any unknown native transaction keeps the room
paused. Empty native history never becomes a fabricated acknowledgement.

One native operation owns the listener and a 15-second delivery window. The SDK
owns network sending and retry. Primitive receipts, pending IDs and terminal
reasons are copied under the callback/control lock. Valid duplicate receipts are
idempotent; multiple provider event aliases for one original transaction are
retained. One event cannot acknowledge different transactions. Collections are
bounded to 128; unknown mutations, malformed identities and overflow terminate
the window as unresolved without discarding earlier valid receipts. Provider
error descriptions are not copied into diagnostics or UI.

Child release requests stop **before** joining its native operation. This pauses
the room and wakes a pending delivery waiter. If stop races listener acquisition,
that late acquisition cannot enable the room. Ordinary caller cancellation leaves
the native operation owned; the five-second default wait is not used for the
15-second delivery window. Delivery permits a 25-second wait, with a hard internal
30-second ceiling. The existing native-interruption retirement rule still applies.

Finalization pauses only the owned room, cancels/joins its listener, closes handles
and then releases the exclusive writer. Slow listener cleanup retains its handle
for retry. Ignored and late queue callbacks dispose SDK payloads too; malformed
disposal cannot unwind through FFI. A timeout or local stop returns only observed
receipts and leaves the remaining transactions pending. `DRAINED` describes the
captured native window; it does not confirm every application intent supplied by
the caller. The app still needs durable receipt/history reconciliation.

The Android data module now enables host tests using the pinned SDK's public
`NoHandle` fake constructors. Root `check` includes them; HRIS CI builds/tests
that adapter before the emulator and retains the XML reports. Fifteen targeted
Android cases exercise partial acquisition, bounded snapshots, native cleanup,
late enablement, cancellation, partial receipts, timeout and payload disposal.
Nine new common cases cover bounded receipt accumulation and the longer owned
wait. These tests complement the actual SDK exchange.

The real HRIS fixture now resumes through the product lease, cross-checks its
receipt against an independent native observer, commits the original app `Sent`
mapping and confirms the sibling room remains paused. The
[delivery measurement](measurements/collaboration-delivery-2026-11-01.json) records
the actual run and portable/Android validation. The common live-admitted sender,
durable recipient aliases/history, richer chat and product routes remain pending;
this primitive does not accept a complete PRD feature group.

## Durable receipt projection

The [outbound receipt journal](collaboration-receipts.md) now retains immutable
message/device/transaction mappings and all observed event aliases in a separate
encrypted store. `ReconcileCollaborationMessages` restores an interrupted `Sent`
projection from those facts under local ownership and the company gate, including
after ordinary credential expiry. Native acknowledgement must be journaled before
command projection; no queue absence can replace that evidence. The [common sender](collaboration-sender.md) now connects this journal to the
original native mappings. Incoming history reconciliation and product routes remain.
