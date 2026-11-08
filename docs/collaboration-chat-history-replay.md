# Stable stored history during replay

Receiving the same original message, retry alias or redaction again keeps the
existing encrypted records and checkpoint revision. A mixed batch writes only
records whose decoded values changed. Newly discovered aliases, earlier accepted
timestamps, legacy indexing repairs and real redactions still commit atomically.

The encrypted history batch compares decoded records before encoding changes.
The repository skips a commit when that batch is empty, then checks that the owned
checkpoint still matches. Cancellation propagates, and an account replacement or
unresolved checkpoint conflict cannot report a successful replay. Original identity
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

## Reading an original during concurrent synchronization — 8 November 2026

Retirement reads an original by its logical message ID. Separate checkpoint and
document reads could combine different revisions; rejecting any later checkpoint
change also turned an unrelated normal history update into a storage failure.
Both races are reproduced by tests that failed against the previous implementation.

The repository now reads the published document and its checkpoint in one raw
database transaction. It decrypts that coherent snapshot and checks the retained
owner/generation after decoding. An ordinary revision advance no longer invalidates
the captured original. Missing keys, corrupt ciphertext, owner replacement and
cancellation keep their existing failure behavior. Writes still use their original
compare-and-set contract; the use case retains admission and completion policy.

Six actual SQLite and six Chrome/IndexedDB cases pass, alongside root, Android,
Wasm/iOS source and quality checks. [Exact local evidence](measurements/collaboration-history-logical-read-2026-11-08.json).
That lookup correction alone did not eliminate the device failure. The subsequent
diagnosis below locates a different failure during mutation preparation.

## Preparing history while another writer publishes — 8 November 2026

A location-only JDWP observation on the original Bima emulator identifies
`CollaborationHistoryBatch.raw:133` as the first throw, followed by the same
`ChatDelivery_AcquireRetirementHistory_Storage` marker. A record newer than the
separately captured checkpoint was rejected as invalid data. The diagnostic APK used
unchanged build61 sources with a temporary debug flag; the exact accepted release61
was restored afterward. No original history or keys were reset. This was diagnosis,
not a latency benchmark. [Exact evidence](measurements/chat-history-preparation-diagnosis-2026-11-08.json).

Mutation preparation now reads each published document with its matching checkpoint.
A changed checkpoint invalidates only the uncommitted plan. The repository makes at
most three local preparation attempts using the same detached input, without network
acquisition or message dispatch. Hidden-message rules and replacement revision indexes
follow the same consistency rule. Commit compare-and-set failures and uncertain commit
outcomes still return to the owning caller; they are not retried here. Corruption,
original-content conflicts, missing keys and cancellation keep their existing behavior.

Ten real-storage regression cases cover concurrent aliases, first publication, local
hiding, edits, replacement indexes, bounded contention, ownership replacement,
cancellation, corruption and conflicting originals. Six failed on the previous
production code; all ten SQLite and ten Chrome/IndexedDB cases pass with this correction.
The full gate passes 5,611 fresh cases with zero failures, errors or skips, Android
compile/lint (zero issues), shared Wasm/iOS source and quality checks.
[Exact validation](measurements/collaboration-history-preparation-2026-11-08.json).
Release62 original-emulator validation records no delivery-failure marker in one new
send, retains old/new history offline, and is installed on the authorized phone. Overall
latency remains unresolved: [actual device result and limits](measurements/chat-history-preparation-device-2026-11-08.json).
