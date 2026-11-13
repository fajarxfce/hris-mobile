# Atomic replica mutations

ReplicaStore.mutate commits one or two snapshot/delta mutations to distinct partitions under
one current account owner. Every entry retains the existing200-document and4MiB bounds.
All expected checkpoints and replacement generations are checked before either partition
or the optional recovery wake changes. A stale condition or wake-capacity conflict returns
false without changing any of them. A database constraint failure rolls back the whole write.

Room reuses its existing snapshot/delta writes inside one outer transaction after preflight.
IndexedDB replacement, delta commit and the new paired API share one implementation and one
read/write transaction. Tombstone precedence, staged visibility and tag updates retain their
existing semantics. No table or migration is introduced. Inputs are copied before suspension;
raw encryption, application admission, retry and projection policy remain with the callers.

Cancellation can lose a committed response but cannot leave a partially committed pair.
Reading the retained checkpoints recovers that outcome; replay cannot resurrect an already
acknowledged wake. Fourteen real storage methods exercise conflict ordering, actual database
rollback, account replacement, staging, tombstones, mutable input, concurrent writers,
cancellation, response loss and durable reopening on desktop and browser. The focused gate
has30 cases, including existing replication/wake contracts and actual app DI checks. Full
verification retains all15,575 prior identities and adds28 executions, totaling15,603.
Apple test compilation passes; the14 actual Apple executions remain a separate hosted gate.

This is the raw storage contract for a source/index publication. It does not implement the
visible conversation activity index or enable Group chat. Port these10 generic paths to the
starter after mobile acceptance, then integrate typed domain projection policy with the
owning source repositories. Preserve the original Company and Group byte protocols.

Evidence: `docs/measurements/replica-atomic-mutations-2026-11-13.json`.

## Actual Apple and portable verification

Control 43403331941 at 8a48212f21c0e1c18802997fe46aabd4c567fba7 passes 19,059 cases: 13,468 portable and
5,591 actual Apple cases. All 81 new transaction/index/draft/send methods ran on Apple;
the iOS host and 16 push-envelope fields pass. Earlier pending statuses above are superseded
for this source. Later reference storage, command progress and product activation are separate.
Evidence: `docs/measurements/collaboration-space-inbox-hosted-2026-11-13.json`.
