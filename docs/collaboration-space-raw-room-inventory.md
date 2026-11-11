# Local SDK room enumeration

The Android adapter reads room identities and membership from the supplied existing Matrix client.
It performs local enumeration without joining rooms, synchronizing, opening a store or creating a
client. The common datasource contract returns detached DTOs with an exclusive room-ID cursor,
finite ordered records and an optional next cursor. Raw metadata grants no content access.

Page sizes range from one to sixty-four. Selection retains at most limit+1 candidates while reading
the SDK's room list. Membership is read only for returned records. Invited, joined, left, knocked and
banned remain distinct; none implies an automatic join. An exactly full final page does not invent
another page. Cursor-only and empty results still dispose every returned native handle.

SDK8 Client.rooms() returns the whole handle list. The bounded candidate/output storage does not
bound that native allocation, or prove inventory continuity while synchronization changes rooms.
A hard native allocation bound needs a different SDK paging API. Runtime lifetime binding, durable
inventory generation/cursor retention and admitted worker integration remain separate work.

Each invocation attempts to close every acquired Room handle, including omitted or unvisited
handles after a read failure or cancellation. One failing close cannot skip the remaining handles.
The primary failure survives; secondary disposal failures are suppressed without self-suppression.
A final cancellation check prevents returning a page after cancellation during disposal. The shared
Client remains owned by its existing parent and is never closed by this adapter.

Twenty-four Android host cases cover ordered/exclusive/empty/exact-limit/large pages, membership,
invalid inputs, SDK identity/membership/enumeration/disposal failures, duplicate selected identities,
detached metadata, cancellation at each acquisition/read/disposal boundary and successive pages.
Fifteen existing raw-event, ten room-source and two application-binding cases remain controls.
These use NoHandle fakes and do not establish native SDK execution or actual memory consumption.

The focused gate verifies51 cases and shared Wasm/iOS metadata compilation. Full local validation
verifies 12,346 cases with preserved prior identities and unchanged retained dependency closures,
including Android host, release compilation/lint, browser and proof APK/unit checks. The portable CI
timeout increases from35 to60 minutes after job130558503933 exhausted its earlier limit while
compiling the app. Required tests and application filters remain intact.

Raw observation and Original production remain disabled. No phone installation, live deployment,
Apple native inventory capability or end-to-end recovery is claimed by this increment. All54
communications PRD groups remain open. This SDK adapter has no generic Starter change.

[Exact validation evidence](measurements/collaboration-space-raw-room-inventory-2026-11-11.json).
