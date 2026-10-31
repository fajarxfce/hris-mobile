# Owned native timeline

The Android protected runtime now owns room/timeline children as well as classic
sync. `openTimeline` is a platform data operation over an already-known encrypted
room. It does not join an invitation, enroll a device, restore a second SDK client,
resume delivery, or grant HRIS access. A common admitted content port and feature
routes must still connect it to the product.

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
