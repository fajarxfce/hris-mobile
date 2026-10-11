# Replication storage

Single-record observers return the active checkpoint and the requested document
from one read transaction. A replacement generation remains invisible until
publication. This lets a repository decrypt a detail record against the same
generation/access envelope as the list, including after removal or account changes.

`AppDatabase.replicas` is a raw storage contract for bounded, encrypted resource
replication. It performs no HTTP, permission checks, DTO-to-domain mapping, or
retry policy. Feature repositories provide authenticated ciphertext; feature use
cases own bootstrap, hydration, cursor selection, expiry, and access recovery.
This storage capability alone does not connect a business API.

Each active account session has separate named partitions. A partition retains
one visible generation and at most one replacement generation. `begin` compares
the observed generation before creating staging storage. `commit` compares the
generation and revision, then saves documents and the next encrypted checkpoint
in one transaction. `publish` makes the replacement visible and removes the old
generation atomically. Readers see no partially hydrated replacement.

`replace` publishes one complete bounded snapshot, or conditionally removes it,
in one transaction. It compares the full observed checkpoint, including the
generation, revision, payload and publication state. A new snapshot uses a fresh
generation; competing replacements have one winner. This supports small complete
records without publishing an intermediate staging state. It has the same session
checks and batch limits as incremental replication. Snapshot admission and expiry
remain use-case policy.

Within a generation, document versions never move backward. A deletion at the same version removes
the visible document and retains a tombstone; an older or equal-version upload
cannot restore it. A newer version can replace it. Ordering keys must be unique
within a generation, normally by including the resource ID. They contain no
message text, names, salary, tokens, or server cursors.

Writes contain at most 200 distinct documents, 128 KiB per ciphertext, and 4 MiB
in total. Checkpoints are at most 32 KiB. Reads return at most 200 visible rows,
using explicit ordering keys. Duplicate ordering keys abort the entire batch,
including its checkpoint; they never silently discard a document. Room uses
explicit insert/update operations because its upsert fallback can swallow a
conflicting secondary index when no primary-key row exists.

Account activation clears replication metadata and resources in the existing
account-cache transaction. All subsequent reads/writes check the originating
session. An old generation, revision, or account cannot publish, recreate, or
delete a replacement account's data. A company/access reset remains an explicit
use-case decision through `clear`.

Cancellation leaves an absent or complete transaction, never a partial batch.
An admitted IndexedDB write finishes atomically and notifies existing observers;
cancellation still propagates to its caller. That outcome does not authorize a
later UI result or advance any separate in-memory cursor. The database owner
closes its connection; a blocked IndexedDB upgrade also closes a connection that
arrives after the original open was rejected.

The shared SQLite/IndexedDB contract checks staging visibility, publication,
concurrent checkpoint/snapshot writes, conditional removal, rollback, tombstones,
bounded pagination, scope replacement, cancellation, and restart. Migration checks cover the existing
SQLite account/inbox and the browser upgrade path.

Validation: the workspace `:check`, Android dev debug build, and database
`wasmJsBrowserTest` passed on Linux. This does not establish device performance
or complete a feature's encryption and synchronization integration.
