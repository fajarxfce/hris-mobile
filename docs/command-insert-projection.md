# Atomic command and checked projection insertion

CommandStore.insertAndMutate retains one new durable command, one or two prepared replica
mutations and a fresh recovery wake under the same local account transaction. A source check
can guard a projection without rewriting source bytes. Replica/wake conflicts return null and
retain nothing. Scope, resource and command capacity preserve existing insertion results.
Constraint failures roll back every store. No schema or key migration is needed.

An identical active or archived immutable command returns AlreadyStored without repeating the
projection or wake. Advanced command state is preserved. This proves retained identity only;
callers must authenticate evidence of the original joint handoff instead of assuming an older
single-ledger command published a projection. Replaying an edit must not replace a later edit.

Room uses one immediate writer transaction and rollback on a failed replica condition. IndexedDB
uses the existing replica transaction with command preflight and insertion; command, replica and
wake object stores commit or abort together. Existing mutation/archival Boolean contracts remain
unchanged. Their internal JavaScript result envelope now also carries insertion results.
Inputs, nested documents and tags are detached before suspension. Late native/browser storage
completion remains joined before cancellation returns; uncertain replies require local readback.

Focused47/full16619 pass, preserving all16587 preceding identities plus32 JVM/browser cases.
Fifteen shared methods cover commit/observers, source/index/wake conflicts, immutable active and
archived replay, ownership renewal, resource/identity/capacity, invalid input, constraint rollback,
competing writes, detached input, cancellation, response loss and reopening. Two additional
SQLite fault injections abort command insertion and a later replica checkpoint write. Existing
archive/mutation controls remain covered. Apple compilation passes; actual execution is separate.

No edit use case, inbox producer, product graph or live service is activated here. Actual authoring
must publish authenticated immutable handoff origin alongside the intent, admit the exact visible
canonical target, preserve placement/draft and reconcile outcome from provider evidence. An ACK
alone never proves application of an edit. The generic nine-file storage change and tests should
now be ported to Starter and independently checked; HRIS policy stays downstream.

Evidence: `docs/measurements/collaboration-command-insert-projection-2026-11-13.json`.
