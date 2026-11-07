# Atomic original room request handoff

A retained Fenced Room can now hand off to exactly one Pending replacement in one
raw database transaction. The original request and outcome ciphertext remain intact;
its authenticated archive names the successor, and the replacement request names its
predecessor. This is local durable linkage, separate from current native/device
admission, room publication, SDK messages and product routing.

## Exact records and compatibility

The pure handoff policy requires a validated unlinked Fenced Room, a distinct valid
replacement ID, the exact predecessor ID and unchanged API/account/space/conversation/
peer coordinates. A replacement may carry the newly admitted native/device request;
the owning flow must acquire those facts before preparation. Device wall-clock order
is not used as server non-execution evidence. Pending, Unconfirmed, Recorded and
already-linked originals cannot authorize this handoff. Immediate self/cycle links
and cross-resource input are rejected.

Existing immutable requests stay schema1 with the same partition/key/v1 authenticated
envelope. Only replacement Room requests use request schema2 with a predecessor ID;
Open and ordinary Room requests retain their original encoding. Outcome schema and
exact original ciphertext stay unchanged. The forward link has its own authenticated
successor purpose, original ID and original outcome revision. It is archive metadata,
not a fake receipt or a changed remote outcome. Old Recorded archives still require
their exact existing outcome ciphertext. Older applications are not promised support
for the new replacement request.

The repository verifies the full expected active record, seals the new request/link,
and invokes existing CommandStore.archiveAndInsert. That transaction removes the
original active resource only with insertion of the replacement. Shared capacity128
is retained, including handoff while full. A new predecessor-bearing Room cannot be
inserted independently. Exact replay of an already retained replacement remains valid.
Archived originals remain findable by ID and cannot be reactivated or mutated.

## Interrupted and competing work

A lost handoff response is recovered by reading the original forward link and the
known successor's immutable predecessor. There is no implicit repository retry or
new operation allocation. Competing handoffs produce one winner, one original archive
and no losing replacement. Failed/cancelled writes preserve the old active fence.
Missing keys cannot regenerate over retained active or archived records; account
changes remain guarded by the raw store. A second handoff retains both earlier
archives and only the immediate current links, without scanning old archives.

[The owning preparation flow](collaboration-space-room-preparation.md) is now locally
validated. It checks both ends before following a retained link and bounds continuation.
A fence alone grants no provider/content authority, and setup operation IDs remain
separate from original message transaction IDs. No raw database API or Starter
foundation change is required for this HRIS-specific integration.

## Validation

[Exact source and results](measurements/collaboration-space-room-successor-2026-11-07.json):
focused40440 passed102 cases/216.321s on4,406
unchanged inputs. Full45951 passed3,777 fresh cases/460 suites/
511.466s on4,406 unchanged inputs, zero failures/errors/skips/lint.
Root check, Android host/release lint, format/architecture/Detekt,75 explicitly verified
Browser cases and shared Wasm passed. Actual30 command-storage and8 policy cases ran
on Desktop and Browser alongside22 owning-resolution,4 resolution-storage,3 Open-storage
and8 fence-transport regression cases. All local jobs are reaped; source CI remains.

Nine new actual-storage cases exercise exact ciphertext and both references across
restart, independent-insert rejection, competing handoffs, an actual committed write
with lost return, failure/cancellation, authenticated malformed links/request schemas,
full shared capacity, foreign ownership/missing keys and chained handoffs with unchanged
old archives. Two additional pure-policy cases cover authorization shape and invalid
terminal links. Existing Open replay, Room resolution, fence storage/transport, legacy
archives and ownership regressions remain in the same gates.

No owning successor preparation, SDK operation, new APK or phone latency acceptance
is included. Existing Apple wrappers inherit the storage cases; new source CI remains.

Current preparation acceptance is separate from this original storage slice; dispatch,
published binding, original SDK activation and product routing remain to be connected.
