# Local physical room resolution

Follow-up: [the single incoming worker](collaboration-space-physical-worker.md) now consumes known physical bindings with
source-aware selection and bounded deferral. Raw collection remains inactive.

An incoming Matrix room locator can now be transferred to a retained logical room without
acquiring provider metadata. This increment adds the local conversion and downstream source
checks. The application worker and raw collector are not enabled by its DI registrations.

`ResolveCollaborationSpaceIncomingBinding` belongs to the one incoming worker. It captures the
exact installed original source, checks local directory/native-parent ownership and looks up the
owned registration for that device. The registration supplies the configured Matrix server name;
the opaque room ID and Matrix user ID never select a server. Current registration, device,
platform and Matrix user must match. Registered provider facts do not grant content access.

The encrypted local binding lookup comes before remote metadata acquisition. Both published SPACE
rooms and retained LEGACY bindings are validated with their original coordinates. A missing binding
is an explicit scheduling outcome; it does not create a room, fetch metadata or discard the hint.
Ordinary credential age does not block owned local work. Confirmed denial still wins when the
runtime has already closed or a pending storage read fails.

The pure conversion transfers only the exact captured physical generation. New logical work keeps
its original generation, FIFO position, optional head, source and retry delay, and starts a Latest
provider wave. This also covers unknown heads and limited-history hints. Membership hints alone
do not revoke local facts. Conversion works at the combined 64-room limit without adding a slot.

Every unfinished logical task retains its phase and source. A physical hint for the same logical
room waits separately until that task completes; neither matching heads nor an absent legacy
source marker establish cross-source coverage. Other physical rooms, source positions, inventory
loss generations and the independent binding scan are preserved.

One exact queue replacement keeps the existing wake. It does not schedule another turn. A lost
write reply is reconciled against the exact transferred logical task. A concurrent serial producer
may already have advanced its cursor or requested a newer physical generation; those facts do not
invalidate the transfer and are never overwritten. Unconfirmed retention never blindly repeats
the write. The incoming worker must serialize conversions with logical progression/completion.

`LoadCollaborationSpaceIncomingRoom` now checks a source-bound task against the exact installed
original and its retained source identity. The source must remain current across suspended local
reads; a different store, Matrix user/device or native parent cannot publish the old task. Existing
null-source work keeps its established local read path without acquiring an SDK source.

## Validation and integration

Focused validation passed 168 cases: 84 desktop and the same 84 in a browser,
including all prior selected case IDs. Full validation passed 11,264 cases: 9,226 fresh runs and
2,038 exact accepted unchanged controls with verified dependency closures. Every prior case ID is
preserved. Format, architecture, detekt, Android host/release compile/lint, browser/Wasm and iOS
metadata gates pass. [Recorded measurements](measurements/collaboration-space-physical-resolution-2026-11-11.json)
identify the exact source and APK. Private evidence retains the earlier
70-case candidate before downstream source guards, both source captures and all formatter results.
The current source has 4,888 inputs and nine changed/new source/test files.

Before worker integration, distinguish an ordinary queue CAS miss caused by concurrent sync from
invalid ownership or confirmed denial; the current standalone conversion reports a conflict and
does not retry. The worker needs a bounded retry for that normal contention case.

Next integrate bounded physical selection and deferral into the single incoming worker. Selection
and rescheduling must agree on the active source so another store's retained work cannot cause an
immediate loop. Remote published-binding lookup and unpublished Company originals need separate
admitted paths. Provider inventory, stored-key recovery and owned raw collector activation remain
required. No new phone latency, native SDK execution or Apple execution acceptance is claimed.
All 54 communications PRD groups remain open.
