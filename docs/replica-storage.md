# Replication storage

Single-record observers return the active checkpoint and the requested document
from one read transaction. A replacement generation remains invisible until
publication. This lets a repository decrypt a detail record against the same
generation/access envelope as the list, including after removal or account changes.

`observeCheckpoint` reads only the partition's metadata. Like `checkpoint`, it
reports staging progress when a replacement exists; it does not expose staged
documents. Collectors can react to committed sync progress without reading or
decrypting resource payloads. Account replacement and partition removal emit an
absent checkpoint. Cancellation releases the collector's database observation.

`AppDatabase.replicas` is a raw storage contract for bounded, encrypted resource
replication. It performs no HTTP, permission checks, DTO-to-domain mapping, or
retry policy. Feature repositories provide authenticated ciphertext; feature use
cases own bootstrap, hydration, cursor selection, expiry, and access recovery.
This storage capability alone does not connect a business API.

`ReplicaScope(sessionId, accountKey)` separates the active request from the stable
API/account owner. Company/collection identity belongs in the partition. A null
account key explicitly selects temporary session storage. Account keys must be
unambiguous and include the API audience; they are not access tokens. A partition retains
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

Account activation clears only temporary session replicas. Account-owned facts,
staging generations and tombstones remain in separate composite keys. Every read
and write checks both the live session and its active account binding within the
same transaction. Logout hides retained facts; a new login can read its own account
with a new scope. An old request cannot read, publish, recreate or delete those
facts after replacement. `clear` removes only its admitted account/partition.
The use case decides when confirmed revocation requires removal or a new cursor.

`containsRecords(prefix)` checks retained generations across inactive accounts
without returning their payloads. Encryption repositories use it to avoid creating
a replacement content key over unreadable existing data. It neither switches
accounts nor makes an inactive account readable.

Room schema 9 and IndexedDB schema 8 preserve the preceding session-owned rows,
active/staging checkpoints and deletion markers. Migration adds a separate owner
key rather than relabeling ciphertext as belonging to an account. Features choose
when to acquire or migrate their encrypted account projection; the database does
not rewrite feature formats or authorization policy.

Cancellation leaves an absent or complete transaction, never a partial batch.
An admitted IndexedDB write finishes atomically and notifies existing observers;
cancellation still propagates to its caller. That outcome does not authorize a
later UI result or advance any separate in-memory cursor. The database owner
closes its connection; a blocked IndexedDB upgrade also closes a connection that
arrives after the original open was rejected.

The shared SQLite/IndexedDB contract checks staging visibility, publication,
concurrent checkpoint/snapshot writes, conditional removal, rollback, tombstones,
bounded pagination, account/API/company isolation, late requests after a new login,
logout, cancellation, and restart. Both migration suites retain published/staged
rows and equal-version tombstones from the preceding database schema.

Validation: the workspace `:check`, Android dev debug build, and database
`wasmJsBrowserTest` passed on Linux. This does not establish device performance
or complete a feature's encryption and synchronization integration.
