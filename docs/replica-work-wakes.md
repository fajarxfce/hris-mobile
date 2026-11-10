# Replica mutations and durable work

`ReplicaStore.replace` and `commit` can retain a pending work marker in the same
transaction as the replica mutation. Callers supply an optional immutable
`ReplicaWakeRequest(key, generation, maxKeys)`. Omitting it preserves existing
replica behavior. The raw database does not choose task keys, create generations,
schedule work, retry, or decide account/content authorization.

The marker contains only a registered task key and a fresh, non-reused caller
generation. It carries no account identity, credentials or document content.
Existing application-global `WorkWakeStore` reads and acknowledgements consume it;
account ownership remains part of the replica mutation and consuming use case.

## Atomicity and conflicts

Current account ownership, expected replica generation/revision, wake generation
and key capacity are checked within one write transaction. A stale replica,
reused current wake generation or full new-key capacity returns false without
changing either store. Validation and technical exceptions preserve their existing
raw behavior. Constraint failures roll back both document/index changes and the wake.

The current wake generation cannot be reused even if it is still pending. Otherwise
a consumer holding generation G could acknowledge G after a writer added data it
never observed. A writer must commit a fresh G2; acknowledging G then cannot erase
G2. Reusing an acknowledged G also fails. Ordinary `WorkWakeStore.request` retains
its existing idempotent behavior; the stricter rule applies to a new replica mutation.

A lost or cancelled response may leave a complete committed transaction. The caller
recovers exact owned data/checkpoint facts before deciding what remains to schedule;
it must not assume failure means the mutation never happened. Pre-cancelled calls
cannot start a write. An admitted IndexedDB transaction finishes atomically before
its owner releases it. No platform permits a half-written data/wake pair.

## Layer ownership

Room's owning `ReplicaDao` accesses the existing wake table within its replica
transaction. It does not call another datasource or repository. IndexedDB includes
`work_wakes` in the existing account/replica write transaction only when requested;
lookup, capacity validation and writes use that transaction. Payload encoding and
bounds checks occur before starting it. Notifications follow a successful commit.
The schema, encryption ownership and existing task acknowledgement API are unchanged.

A producer use case can request OS scheduling after a confirmed atomic mutation.
Failure or cancellation of that scheduling request leaves its durable marker for
later lifecycle recovery. Ordinary reads of already-retained data should not create
a new work generation. The application-specific producer/consumer orchestration is
separate from this generic primitive and is not part of Starter's database layer.

## Validation

The focused contract passes 11 SQLite and 11 real browser cases. The full database
suites pass 89 desktop and 87 browser cases, including capacity/consumer races,
pending/acknowledged generation reuse, constraint rollback after an actual row write,
stale owner/checkpoint, lost responses, cancellation, staging/removal, compatibility
without a wake and file/IndexedDB reopen with reauthentication.

The required application/platform gate passes with 7,468 validated results. Of these,
4,012 are passing reports from the same-source full/repair runs; 3,456 retain exact
accepted predecessor report identities. The final command reused those results.
The source and library manifests, current XML and archived results agree.
[Recorded evidence](measurements/replica-work-wakes-2026-11-10.json).

The first full gate exposed a stale generated test delegate after the interface
signature changed: its bytecode still had the old mutation methods. The unchanged
UI integration test failed with `AbstractMethodError` before its intended storage
fault. Non-incremental recompilation regenerated both methods; all 714 application
tests then passed, preserving all 90 AppFlow case identities. No UI change, timeout
extension or weaker assertion was needed. The failed run and old class are retained.

Apple execution and the downstream Starter port remain pending. No HRIS producer
is wired, and no release/live change is claimed here. The existing native proof APK
predates this interface change and does not establish its runtime acceptance.
