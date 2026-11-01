# SDK history ownership and pagination limits

The Android binding is pinned to `sdk-v26.10.23`, backed by Matrix Rust SDK
`8de215a0aa0026afa5754ae5f05a6d168d1a1d60`. These details were traced before
adding product pagination; none is a measured large-room capacity claim.

## Retained focused caches

[`Caches::event_focused`](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/crates/matrix-sdk/src/event_cache/caches/mod.rs)
keeps a strong `HashMap` keyed by event ID and thread mode inside each room's
caches. A repeat uses that cache; only a new entry calls `start_from`. Disposing
our timeline and listener does not establish that the SDK removed its focused
cache. The event-focused cache's events live in memory, separately from the
persisted room event store. This is additional to the room event-cache cloning
and diagnostic-model allocation documented in [history](collaboration-history.md).

`SdkHistoryFocuses` now limits the owned Android history adapter to **16 distinct
(room, anchor) keys per SDK client lifetime**. The current thread mode is fixed
to `Automatic(false)`. Reservation occurs inside registered
child initialization, before native cache creation. Concurrent requests share the
same bound; known keys remain readable at capacity. A failed or cancelled native
creation retains its reservation because its cache outcome can be uncertain.
Rejected child registration creates no reservation. Window disposal never resets
this accounting. Closing that client and creating its replacement gives the new
client a fresh budget while its protected SDK database and keys remain preserved.

Capacity exhaustion fails with `client.collaboration_history_focus_capacity`
before another native window opens. It neither evicts a cache nor restricts owned
local history. Product-managed client reopening before this limit remains necessary
before continuous pagination/UI acceptance. This bounds distinct owned focus keys,
not every allocation in the SDK, all direct uses of a raw native client, global
room caches or process memory. The current adapter does not paginate these caches.
Any later extension must also bound the number of pages retained in each key.

## Pagination completion is not a UI snapshot barrier

[`Timeline::paginate_backwards`](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/crates/matrix-sdk-ui/src/timeline/pagination.rs)
returns the focused cache's `hit_end_of_timeline` flag. The
[event-focused cache](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/crates/matrix-sdk/src/event_cache/caches/event_focused/mod.rs)
updates under its write lock and broadcasts diffs before returning. A separate
[`event_focused_task`](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/crates/matrix-sdk-ui/src/timeline/tasks.rs)
applies them to the public timeline later. Registering a listener immediately
after pagination can therefore still capture an older initial Reset. A delay or
empty list is not proof that all paginated events were projected.

The current `readOriginalHistory` intentionally remains a finite initial snapshot;
it does not invoke native pagination or claim a provider cursor boundary. The
head-discovery HTTP request owns no provider position either.

The binding's `Room.load_or_fetch_event` is not an original-JSON replacement:
[`TimelineEvent`](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/bindings/matrix-sdk-ffi/src/event.rs)
exposes identity, timestamp and typed content, without the complete raw original
content/custom fields needed for logical IDs and canonical fingerprints. Reusing
it alone would weaken the already-proved original-content contract.

## Provider metadata and remaining implementation

The next adapter uses [bounded provider-page metadata](collaboration-history.md#provider-page-metadata)
through the existing gateway and current SDK credentials. This obtains explicit
opaque positions and event references, including empty advancing pages, without
treating the asynchronous UI timeline as the source of traversal progress. The
SDK still supplies original decrypted content through owned context windows.
The metadata adapter itself does not persist or acknowledge those references.

An encrypted traversal repository now stages references and positions atomically.
The next common operation must surround that staging with fresh HRIS admission,
acquire originals through bounded SDK work and retain unresolved references.
Cancellation or a lost outcome must not cause an
unbounded repeat of native pagination or a false history-end acknowledgement.

A fresh timeline view of the **same** existing focus is a candidate snapshot barrier:
its awaited initialization subscribes to the focused cache under its read lock and
builds initial items from that cache. The SDK key ignores initial-context size.
This remains an alternative if native pagination is introduced later. Validate
it with delayed-update tests and actual SDK evidence before wiring it;
retain/dispose both views explicitly and account for the additional native room
cache clone. It is not implemented or accepted by the focus-budget change.

Keep unsupported/undecryptable events durable when publishing page progress, and
separate provider traversal order from message timestamps. Add bounded per-focus
page work, explicit client reopening with preserved keys/cursors and ACK-before-
journal recovery before product paging can run indefinitely. No SDK fork, separate
crypto store, new credential refresher or provider polling loop has been added.
