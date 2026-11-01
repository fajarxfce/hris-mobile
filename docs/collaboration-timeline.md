# Owned native timeline

The Android protected runtime now owns room/timeline children as well as classic
sync. `openTimeline` is a platform data operation over an already-known encrypted
room. It does not join an invitation, enroll a device, restore a second SDK client,
resume delivery, or grant HRIS access. `EnterCollaborationConversation` adds the
common admitted entry port described below. Feature routes and owned offline
projections must still connect it to the product.

## Admitted entry

`EnterCollaborationConversation` captures current HRIS admission, reads the active
device for the current native platform, verifies the owned direct conversation
and requires its matching READY room. It rechecks the owner after every pending
read, including a failed read. It then invokes the runtime repository with the
observed room ID. No setup registration, bootstrap, room provisioning, device
reset or parent-runtime restoration is implicit in entry.

The repository requires an already-installed lease matching the complete owner
and device. It shares that lease's sync producer and acquires a child that joins
the room before observing it. Partial join/timeline initialization still belongs
to the parent. Replaced local sessions cannot borrow another runtime. The common
`CollaborationTimelineLease` binds owner, device and Matrix room; the entry use
case verifies all three before handover. A cancelled/late result or new access
denial closes only the acquired child. Cleanup failure cannot replace the
authoritative operation result.

Structured SDK Matrix errors retain unauthorized/access-denied classifications.
Missing rooms have a separate unavailable code; rate limits use bounded delays.
Unknown provider codes and exception descriptions never enter domain parameters.
Received denials survive simultaneous local credential/shutdown failures and a
failed post-request session read. Only explicit admitted acquisition can reopen
a consumer's denial gate; an ordinary failed retry is not permission. This
stateless entry operation does not itself persist the consumer's read admission.

Entry is a live operation. The child's Flow is a primitive observation resource,
not a renewable authorization token or an offline-history repository. Product
consumers still need scoped observation/disposal and the separate owned read
model, preserving cached facts through ordinary expiry or unavailable network.

## Lifetime

The parent reserves one of eight child slots before native acquisition. The
resource holder exists before opening the room, timeline or listener, so partial
failure also has an owner. Initialization belongs to the parent coroutine scope.
A caller waits up to five seconds; a cancelled or timed-out caller starts owned
cleanup and waits at most five more seconds. The producer and its retained slot
survive an unfinished wait. There is no automatic initialization retry.

Each child shares one disposal producer between screen and application callers.
Failed termination retains the same resource and capacity until explicit retry.
Closing one screen leaves siblings open. Parent close first rejects new children,
then closes all retained children before SDK database draining and store unlock.
An unfinished child prevents parent release. Credential persistence failure also
stops children; explicit protected runtime reopening is required after recovery.
Structured sync denials gate snapshots before closing children. A denial returned
by an in-flight sync during disposal remains authoritative even if cleanup fails.

The pinned Rust SDK calls the initial timeline RESET synchronously before
`addListener` returns its task handle. The source installs its projection lock and
state before invoking this method. Disposal gates callbacks, cancels and awaits
the task, then destroys timeline and room handles. Native callback values are
copied to primitive DTOs and disposed; SDK references never enter domain or UI.
The repository maps DTO snapshots without starting another unowned collector.

## Projection

The ordered vector retains virtual/non-message positions while applying deltas;
filtering before application would change the SDK's indices. Every batch either
produces one complete immutable snapshot or fails without publishing partial
changes. All eleven vector operations are supported, including initial/reset,
local-echo replacement, history prepend, clear and truncate. StateFlow may
conflate complete snapshots; raw deltas are never dropped or reordered.

Current defensive limits are eight children per protected runtime, 512 vector
entries, 4,096 changes/copied entries per batch, 16,384 characters per text body
and 1,048,576 body characters in a published snapshot. Exceeding a limit gates
this observer; it does not truncate indexed SDK state or delete persisted data.
A product history window and paging/recovery UX are still required before large
conversation acceptance. These limits are not capacity measurements.

Text, redaction, unavailable decryption and unsupported message kinds have
separate representations. Event ID, transaction ID, sender, stable timeline key,
send status and edited state are retained. The custom logical message ID is read
only from the event's top-level content with size/depth checks. Local echoes have
no `latestJson`; their logical ID stays unknown until durable intent/transaction
reconciliation supplies it. This projection does not provide cross-event logical
deduplication, edit/delete policy, offline catalog recovery or message submission.

Stopping clears only the volatile observer and publishes a closed result. It
does not delete the encrypted SDK database or an app-owned message history.
Ordinary freshness, owned offline facts and confirmed access denial still need
their separate domain admission/read models above this platform handle.

## Source audit and verification

Pinned Android SDK `26.10.23`, Rust
[`8de215a0`](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/bindings/matrix-sdk-ffi/src/timeline/mod.rs)
defines initial RESET ordering in `add_listener` and documents that
`LazyTimelineItemProvider.latest_json` returns none for local echoes. The Android
FFI API was inspected from the pinned AAR, including disposable event records,
task completion and all vector variants.

The actual HRIS fixture observes both encrypted replies through product timeline
leases after process restart. It closes one child explicitly, checks that the
sibling still works, then checks closed snapshots after parent disposal before
instrumentation can report completion. The provider-only fixture remains separate.
[Measurement](measurements/collaboration-owned-timeline-2026-10-31.json) records 15
new common cases, focused checks passing in 70s, final root/browser/Android/Wasm/
lint passing in 245s (2,160 desktop cases, 64 data browser cases, zero lint errors),
and actual SDK run `5aea81cc` passing in 34.340s (53s Gradle, 32 driver cases).
All 15 Platform/120 Mobile source hashes match before and after execution. One
provider-busy key upload returned 503 before successful completion. Emulator
stopped; no phone, production service or schema change is made.

## Admitted entry and interrupted-sync verification

[The 1 November evidence](measurements/collaboration-sync-recovery-2026-11-01.json) verifies the common entry port,
structured denials, bounded transport recovery and the actual post-restart 429.
Fixture failures and corrections remain recorded; product route/history/offline
acceptance is still pending.

## Native history-window research

The pinned Rust SDK `8de215a0` room event cache auto-shrinks to its last stored
chunk when no subscribers remain ([source](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/crates/matrix-sdk/src/event_cache/caches/room/state.rs#L467)).
Its inspected `TimelineFocus.Live` configuration exposes thread visibility, while
event focus takes a target/context count. This is source inspection, not window
acceptance. Design and test bounded product windows without clearing SDK identity
or crypto state before replacing the current 512-item defensive gate.

## Queue reconciliation research for the product sender

Source research at Rust SDK `8de215a0aa0026afa5754ae5f05a6d168d1a1d60` confirms that
[room send-queue subscription](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/bindings/matrix-sdk-ffi/src/room.rs#L967)
replays the captured local transaction IDs synchronously before returning its task
handle. This is stronger than waiting for a quiet timeline. Its FFI updates contain
transaction IDs, not full message content. The existing native timeline mapper can
read the logical ID from `event.lazyProvider.latestJson()`; timeline-local-echo
hydration itself is asynchronous. The global queue subscription acquires its initial
local echoes before subscribing and is not assumed to have the same snapshot boundary.

The product enqueue/recovery owner still needs implementation and crash tests.
The proposed boundary is: retain the immutable logical intent, pause native message
delivery, enqueue once, reconcile captured native transactions with hydrated logical
IDs, persist the mapping, and only then admit delivery. Startup must restore paused
and finish that reconciliation before enabling queues. A crash after SDK enqueue
and before the application mapping must recover from the paused SDK queue; an empty
or not-yet-hydrated timeline is not proof that no enqueue occurred. Previously mapped
in-flight messages retain their original transaction through acknowledgement loss.
Do not introduce a second retry owner or generate a replacement native transaction
for an uncertain intent. Native protocol recovery proofs alone do not accept this
product sender, its bounded history windows or cross-family recovery.
