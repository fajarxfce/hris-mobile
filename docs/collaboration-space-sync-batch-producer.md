# Original sync batch retention

The existing SDK sync can now supply detached response batches to a serial local retention use
case. A response position and its requested work share one encrypted transaction. The application
collector remains disabled until physical room resolution, provider inventory and key recovery
have owning consumers. This increment does not claim live incoming push or phone latency acceptance.

## Ownership

`SdkSyncLifecycle.observeBatches` observes its already acquired connection. Collection does not
acquire a client, start sync or retry failed acquisition. Cancelling a reader leaves the native
producer with its lease; stopping the lease ends current and future batch readers, including when
native acquisition or its response completes late.

The Android sync repository captures the exact installed lease and reacts to registry retirement,
credential failure and sync termination. `incomingSource` is a synchronous registry lookup, with
no credential read, native identity acquisition, device-status request or transport request. Source
identity comes from the original protected record: full lowercase SHA-256 store ID and Matrix
user/device. A successor logical Space never supplies a substitute store hash.

The raw mapper reduces each bounded room change to a physical locator, optional head, membership
and limited-history requirement. Unrepresentable, duplicate or overflow locators retain explicit
inventory loss. Positions belong to that response; a later SDK store read cannot relabel it.

`RetainCollaborationSpaceSyncBatches` is one application-container instance. Its cold flow owns one
serial subscription; another collector waits without subscribing or acquiring a client. The caller
must cancel and join the old collector before selecting another runtime. Domain owns local session
and directory admission, source matching, retention policy and scheduling. A storage failure never
becomes a native network failure or restarts sync.

## Durable behavior

The pure policy validates and captures bounded collections before suspension. Same-source/same-next
position is a replay, including after completed work has been removed. Cursor-only responses leave
pending work, independent binding scans, wakes and retry delays intact.

A first source, missing/mismatched previous position, explicit SDK loss or room overflow requests
provider inventory in the same proposed transaction as the next position. A later loss has a new
inventory generation so an older completion cannot discharge it. Provider inventory and the local
binding scan remain independent.

Physical hints coalesce only under the same original source and room. An unchanged nonnull head,
membership and no stronger limited-history requirement retain generation, FIFO position and retry.
Changed or unknown heads preserve the pending turn/backoff but require a new generation. The limited
flag remains set until the owning worker resolves it. Membership hints do not revoke local access.

Logical and physical work share the existing 64-room limit. Overflow preserves old work and records
source inventory; no cursor is committed while pretending omitted work is complete. At the 64-source
limit, only unreferenced progress without pending inventory may be retired. This retires a scheduling
record, never SDK files or keys. Protected capacity returns a classified unavailable outcome.

The repository performs one exact snapshot replacement with an optional wake. Admission is checked
after pending operations and before scheduling. If the write reply is lost, readback checks the exact
source identity and response position, rather than requiring the whole mutable queue to remain
unchanged. A worker may already have advanced or completed work. Only this serialized producer may
advance source positions; all worker policies must preserve them. Failed readback does not blindly
repeat the write. A durable wake survives scheduler failure.

## Validation and remaining work

Focused source validation passed 298 cases: 80 domain and 50 data on desktop, the same 130 in a
browser, and 38 Android host controls. This includes actual repository installation/publication and
retirement over inert FFI fixtures, cancellation, source/account replacement, confirmed denial,
full capacity, replay and a worker changing work during uncertain readback. Prior raw tests remain
byte-identical. Full application validation passed 11,196 cases: 9,158 fresh runs and 2,038
accepted unchanged controls with verified dependency closures. Every prior case ID is preserved.
Format, architecture, detekt, Android host/release compile/lint, browser/Wasm and iOS metadata gates
pass. The original expected total omitted 18 common data tests also run on Android host; the audit
now verifies every added class and platform multiplicity. No source change or test rerun was needed.
See [recorded measurements](measurements/collaboration-space-sync-batch-producer-2026-11-11.json).

The frozen 4,882-input source manifest is
`b87a173aeba8d3cff62df1034d7565dd9926c317fe1c06b8eb243db854552b21`.
Native SDK execution, new Apple execution and phone delivery are separate acceptance.

Before activation, add admitted physical-to-logical conversion and a bounded provider-inventory
consumer. Keep an active inventory boundary separate from later requested generations; close all
native room handles on failure/cancellation. Published Space/retained LEGACY resolution uses its
own API. Unpublished Company originals retain their separate route. Confirm actual runtime
publication before attaching the application collector. Stored-key recovery and incoming push
remain required. All 54 communications PRD acceptance groups remain open.
