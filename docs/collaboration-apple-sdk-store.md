# Apple SDK store acquisition

`AppleCollaborationSdkStoreSource` implements the shared directory-lease contract.
It shares `AppleSdkDirectoryLock` with the existing-only inventory adapter, so
inventory, existing store access and explicit acquisition contend on the same
native `owner.lock`. The datasource opens no database or native SDK client and
does not read, generate or replace credentials.

The platform must first provision a private root with the required backup exclusion
and file-protection policy. Acquisition does not create that root implicitly.
Only `acquire` may create its scoped store, database and cache directories or the
original owner file. Creation requests mode0700 for directories and0600 for the
lock, subject to the process umask; existing permissions are never modified.
All descendant operations use anchored descriptors and reject symlinks.

`acquireExisting` never creates or repairs a file. An absent root/store returns
absence; an existing store requires the original regular lock, database/cache
directories and nonempty state/crypto database pair. Both acquisition modes
reject redirected or non-file database entries and known SQLite WAL/SHM sidecars.
Existing ciphertext and sidecars are never truncated or deleted on success,
failure or cancellation.

The lease's `initiallyOccupied` flag records whether database or cache contains any
entry. Directory enumeration stops at EOF or its first retained entry, allowing at
most the two dot entries beforehand. It never builds an unbounded file listing.
This is a file fact; the repository and use case still decide whether an SDK
identity may be created, opened or recovered. An occupied store is not permission
to recreate missing key material.

The lease retains the exclusive native owner after its inspection descriptors
close. Cancellation before lease publication closes the owner. Closing an old
lease twice cannot release a replacement owner's lock. Cleanup attempts every
owned descriptor, retains the primary failure and does not retry close against a
potentially reused descriptor number.

Eight new Apple tests complement the unchanged13 inventory tests. They cover
private creation, no-create absence, original pair/sidecar retention, native and
inventory contention, partial/empty stores, redirected entries, failed cleanup
and cancellation in both acquisition modes. Compilation and native execution
are tracked separately in `.work/apple-sdk-store-2026-11-12`; local format/static checks, ARM64/metadata and21 simulator-test method compilation,
and full `:check` pass. The14,626 unchanged-platform reports retain exact accepted
hashes; no new test executes on Linux. Native Apple execution remains pending. No Apple product binding, SDK publication,
key generation or live Group activation is included.
