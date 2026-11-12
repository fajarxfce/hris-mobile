# Existing Apple SDK inventory

`AppleCollaborationSdkInventorySource` implements the shared inventory contract for
an existing private SDK directory. It supplies raw file facts to
`StoredCollaborationSdkInventoryRepository`; the repository retains protected-record
reading and mapping, and the existing domain use case retains admission policy.
No Apple product binding, native SDK client, enrollment, recovery or store creation
is introduced by this adapter.

The platform must provide its trusted, private, backup-excluded root. Its OS alias
is resolved once. Descendant directories and `owner.lock` are opened relative to
directory descriptors with no-follow flags; the original lock must be a regular
file. A nonblocking exclusive `flock` covers the full inventory lease, including
the repository's later credential read. Independent file descriptions in the same
process contend as well as external owners. Different store IDs remain independent.
No `CREATE`, truncation, deletion, directory repair or database opening occurs.

`AppleSdkDirectoryLock` owns the anchored store descriptor and lock descriptor.
It serializes inspection and idempotent close with an `NSLock`. Closing releases
the OS lock, attempts cleanup of both descriptors, and preserves primary errors.
Close is not retried against a descriptor that the OS might have reused. An old
lease cannot release a later owner's descriptor.

Missing roots or stores return absence. A missing original lock is a failure;
a missing database directory or file supplies absent file facts. Empty files and
cache files cannot replace the original state and crypto databases. Symlinks and
non-file obstructions fail. `O_NONBLOCK` prevents a FIFO in place of `owner.lock`
from hanging during acquisition. Native errno is retained internally without
putting paths or key material in exception messages or lease descriptions.

The datasource dispatches blocking native I/O away from UI. It retains any acquired
owner through synchronous inspection and through the dispatcher return. Cancellation
before publication closes that owner; a lease successfully returned to the repository
is closed by the repository after protected reading. It does not launch a detached
native read whose cancellation could release the directory too early.

Thirteen Apple tests cover no-create absence, invalid names, missing original locks,
empty/cache files, independent owners, a separate native lock owner, trusted root
aliases, redirected descendants, non-file/FIFO obstructions, late cancellation,
and the shared repository's protected-read lifetime. Their native execution is a
separate gate from Linux compilation. Main iOS ARM64 and metadata compilation, all13 simulator-test methods, format,
architecture, static checks and full `:check` pass locally. The full check reuses
14,626 exact unchanged platform reports; it executes no new test locally. Native
execution of the13 new Apple cases remains pending. The first compile-only failure was a missing import of the native
`kotlinx.coroutines.IO` extension; its source and diagnostic are retained privately.

Workspace: `.work/apple-sdk-inventory-2026-11-12`. SDK8 native Swift/Mach-O acceptance,
full existing-store acquisition, local identity reading, private-root composition,
and production Apple runtime binding remain separate work. Android release 91 and
its accepted inputs are unchanged; this adapter is not included in that APK.
