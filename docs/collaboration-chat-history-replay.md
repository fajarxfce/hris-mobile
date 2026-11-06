# Stable stored history during replay

Receiving the same original message, retry alias or redaction again keeps the
existing encrypted records and checkpoint revision. A mixed batch writes only
records whose decoded values changed. Newly discovered aliases, earlier accepted
timestamps, legacy indexing repairs and real redactions still commit atomically.

The encrypted history batch compares decoded records before encoding changes.
The repository skips a commit when that batch is empty, then checks that the owned
checkpoint still matches. Cancellation propagates, and an account replacement or
concurrent checkpoint change cannot report a successful replay. Original identity
and content conflicts still reject the whole batch.

This belongs to encrypted storage. No ViewModel filtering, UI delay or refresh
action masks a changed revision. The native proof continues to require exact page
equality after replay; its assertion has not been weakened.

## Validation

The SQLite regression first failed all three new cases on the previous production
code. With the correction, all 22 storage cases pass. The shared contract also runs
on IndexedDB and checks ciphertext, revisions, mixed batches, process/store reopen,
owner replacement and cancelled reads.

Native CI runs 42163759500 and 42168989689 both stopped at the existing
`HrisProofOriginalHistory.verify:54` replay equality assertion. The latter also
recorded `validating_completion`; the fixture and SDK acquisition had completed.
The [full gate](measurements/collaboration-history-replay-2026-11-06.json) passed
2,353 fresh cases, Android builds, zero lint issues and architecture/format checks.
The first full attempt failed only because temporary generated SDK sources were
scanned by quality tasks; moving those artifacts outside the application fixed
that. The unchanged-source repeat reused tests and claims no new test executions.

The next native run must confirm the corrected product path. This does not accept
the separate raw-event SDK binding or complete the communications PRD.
