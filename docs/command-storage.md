# Durable command storage

`AppDatabase.commands` retains encrypted requests independently of disposable
replicas. Logout and a replacement session revoke access without deleting an
unconfirmed operation. Identity persistence binds the active session to a stable,
length-delimited API-audience/account key. A later authenticated session for that
same account can recover its records; another account cannot read or update them.
This local binding does not grant server permission or offline company admission.

The raw store performs database I/O only. Features encrypt request and outcome
envelopes, map storage results, and own command eligibility, retry, authorization,
conflict review and retention. No HTTP operation starts from a storage callback.
Feature integration is separate from this storage capability.

Content-key creation can use the [atomic credential port](protected-credentials.md).
The raw database does not create or recover cryptographic keys itself.

Each partition retains one operation per resource. The operation ID, resource,
ordering key and request ciphertext are immutable. Updates change only the outcome
envelope and compare its observed revision atomically. Removal also requires that
revision. Repeating an identical insertion recovers the existing operation without
overwriting a later outcome. An operation cannot silently replace another request.

Every scoped query and write checks the current session/account in the same
transaction. Pages contain at most 50 records. Pending storage is limited to 128
records per account and 512 per database, with 128 KiB request and 32 KiB outcome
envelopes.
Cancellation leaves an absent or complete transaction. A committed browser write
still notifies observers if its caller is cancelled. Owners close database resources.

`observeAll` observes the complete selected partition, bounded by the existing
128-command account capacity. It supports summaries that must include retained
operations beyond the ordinary 50-record page. It preserves account checks,
ordering, logout invalidation and cancellation; it does not schedule delivery or
classify outcomes. SQLite and IndexedDB share coverage through full capacity,
outcome changes, removal and replacement login.

SQLite and IndexedDB contract tests cover competing writers, lost local responses,
immutable requests, account/company isolation, logout/re-login, bounded capacity,
cancellation, migration and reopening. Encryption, backend receipts, UI actions,
and physical-device behavior require their own integration checks.

Validation: the Linux workspace check, Android dev debug build, and actual SQLite
and IndexedDB tests passed. This foundation is also ported to Compose Fluent Starter.

## Immutable completion archive

`CommandStore.archive` atomically copies the exact observed command and opaque
completion evidence to `command_archives`, then removes its pending row. The
feature owns eligibility and encryption; the raw store cannot infer completion
from a response, age or state string. The operation fences current account/session
and the complete observed command in that same transaction: ID, resource, sort key,
request/state ciphertext and revision. An equal revision after delete/reinsert does
not make a replaced command eligible. A failed insert/delete rolls both
changes back. Evidence is bounded to 64 KiB of UTF-8; original request/state bounds
remain. An identical complete-record/evidence replay succeeds without replacing anything.
Changed proof, stale or replaced records and missing commands fail without mutation.

`archived` reads one exact account/partition/ID. Ordinary `get` and observations
continue to describe pending rows only. Archived identity remains immutable and
fences future `insert` in its database transaction. The same request/resource/sort
identity returns `AlreadyStored` even if pending is gone; archive-aware callers
then read `archived` and validate their decrypted identity. Different identity
cannot recreate that command. State updates and ordinary removal never change an
archive. A different operation ID may reuse the freed pending resource/sort slot.

The 128-account/512-database pending limits exclude archived rows. Archive reads
use the primary key; there is no unbounded archive scan or silent eviction.
`containsRecords` includes archives from inactive accounts so content keys cannot
regenerate over retained ciphertext. Logout keeps the completion evidence while
revoking access, and reauthentication binds the original account again.

SQLite schema 11 and IndexedDB version 10 add only the archive table/store. Migration
retains existing pending commands and account bindings. Shared contract tests cover
competing updates/archives/inserts, same-revision replacement, immutable replay, lost responses, cancellation,
account fences, pending capacity and byte bounds. Native/browser tests additionally
exercise reopening, the previous-schema upgrade, and an injected transaction abort
between archive creation and pending deletion.

This is a raw storage foundation. A feature must preserve and validate its complete
completion evidence before requesting archival. Receipt-journal cleanup, history
projection, local deletion and retention are separate feature policies; no scheduler
or automatic cleanup is introduced by these methods. Executed validation and the
Starter port are recorded in the [measurement](measurements/command-archive-2026-11-01.json)
and Git handoff. Both repositories pass their full local checks.

## Atomic insertion batches

`insertBatch` publishes 1–128 detached records in one SQLite or IndexedDB
transaction, using the same ownership and account/global pending limits. Every
immutable existing identity must match, including archived completions. Replay
retains the original request, later outcome and archive; only missing records
consume pending capacity. `Stored` means at least one new row committed, while
`AlreadyStored` means every identity was already retained.

Scope, capacity or occupied-resource outcomes insert nothing. An identity or
unique-index conflict rolls back the complete batch, including preceding writes.
Cancellation may leave the complete commit or no commit; it cannot leave a partial
batch. The caller must retain operation IDs and validate encrypted logical intents
when replaying. Randomly encrypting the same plaintext again produces a different
raw request and must not be used to replace a retained ciphertext.

No schema migration, encryption policy, scheduler or feature consumer is added.
Six shared cases exercise replay/archives, rollback, ownership/bounds, capacity,
competing writers/cancellation, and full close/reopen with login replacement.
Actual SQLite and browser checks pass; the new Apple wrapper and Starter port
remain pending. [Evidence](measurements/command-batch-2026-11-05.json) records the
120-case focused and 2,707-case full checks, with unchanged source fingerprints.
