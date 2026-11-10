# Durable command insertion and recovery work

`CommandStore.insertBatchAndWake` writes new durable commands and a fresh pending
work generation in one transaction. A single send uses a batch of one. The caller
supplies a registered key and a non-reused generation through `WorkWakeRequest`,
also used by [replica mutations](replica-work-wakes.md).

Only new commands create work. Replaying an entirely retained batch returns
`AlreadyStored` and leaves the wake unchanged, including when the commands were
archived, their state advanced, or the previous wake was acknowledged. A mixed
batch keeps the original identities and inserts only its missing commands.

Current session/account ownership, immutable command identity, occupied resources,
command capacity and wake admission are checked inside the write transaction. The
existing insertion outcomes retain their meanings; null specifically means a wake
generation or capacity conflict. Neither store changes on a rejected transaction.
An actual database constraint failure rolls back earlier inserts and the wake.

A new mutation cannot reuse the current generation, even while it is pending.
Otherwise an older worker could acknowledge newly added work that it never read.
Fresh generations also prevent an old acknowledgement from erasing a newer batch.
The raw database stores no account/content data in a wake and does not choose keys,
generate IDs, schedule execution, retry delivery or decide business permission.

Room's owning `CommandDao` accesses the existing wake table in its transaction.
IndexedDB adds `work_wakes` to the command transaction only for this operation.
Both validate and detach input before suspended I/O. An admitted browser write
finishes before releasing transaction ownership. Cancellation or a lost response
can leave a complete committed mutation; the caller must read back its exact
commands before deciding what to schedule or retry.

The original `insert` and `insertBatch` APIs leave work markers untouched. There is
no schema migration or rewrite of existing encrypted commands. Application use
cases own the subsequent scheduling request; a scheduling failure cannot undo
the already retained commands and recovery marker.

## Validation

The shared real-database contract has thirteen cases per platform, covering mixed
active/archive replay, generation and capacity conflicts, ownership and immutable
identity, rollback after an actual successful row insert, competing consumers and
producers, interrupted responses, cancellation, bounds, compatibility and reopen.
All 202 database cases pass:102 on SQLite and100 in real Chromium. The full
application gate passes in939.564 seconds with9,320 verified results:3,864 fresh
and5,456 exact accepted reused results, including the focused database gate.
No failures, errors or skipped tests. All4,660 source/library inputs, archived
reports and current XML match. Android host tests, release compilation/lint,
Wasm/iOS source compilation, browser tests, proof APK/unit tests and quality checks
pass. Non-incremental Kotlin compilation avoids the previously proven stale
delegate issue; all90 AppFlow cases pass without changing their assertions.

The first private runner failed during Gradle configuration, before any test ran.
Only its init script was corrected; the failure is retained with the acceptance
[evidence](measurements/command-work-wakes-2026-11-10.json).
Pinned Apple execution and the Starter port remain pending and are tracked in
[the handoff](agent-handoff.md). No new SPACE message producer, native runtime
execution, phone release or live activation is claimed by this raw storage change.
