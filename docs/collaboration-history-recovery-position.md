# Independent history recovery position

An unfinished recovery pass now has its own position in the existing encrypted incoming
history checkpoint. Primary provider pages and their pending seek keep their own positions.
New primary pages preserve the recovery position, including a completed attempt that still
has unresolved references. This storage primitive does not activate automatic recovery.

The domain record keeps active generation, exclusive reference-count boundary, opaque cursor,
completion, unresolved-seen state and one later requested generation. Empty boundaries are
completed and clean; completed attempts carry no cursor or unvisited request. Active/requested
IDs are nonzero canonical UUID strings. Domain validation treats cursors as opaque; encrypted
storage additionally checks the existing reference index encoding and sequence boundary.

Both Company and Space use the owning recovery repository. Its raw storage update changes
only the recovery section of a published incoming checkpoint. Independent primary appends,
reference resolution and pending seeks can advance while the recovery section remains equal.
A competing recovery section or database compare-and-set loses without retry or overwrite.
An exact already-persisted replacement rechecks the still-owned checkpoint and returns without
another write. A missing history partition creates no key, reference, provider position or wake.
Acquisition and mapping stay inside safeStorageCall; cancellation propagates and unknown write
outcomes remain failures for the operation owner to confirm.

Explicit schema4 carries recovery; older Company1/2 and Space3 frames remain readable and keep
their established AAD, namespace and keyed indexes. A new section under an older authenticated
header is rejected, as is schema4 without recovery or the correct provider provenance.
Reference rows are shared and not rewritten by recovery updates. Existing 24,000-byte incoming
plaintext and 32,768-character encrypted frame caps remain unchanged.

Validation covers 10 common domain cases, 8 real-cipher codec cases and 20 actual encrypted
storage cases, each storage case exercising both Company and Space. Cases include reopen,
reauthentication, account/audience/lane/device/room isolation, primary append/latest-wave/seek/
resolution interleavings, finite boundaries, competing recovery, database CAS loss, cancellation
before and after commit, lost replies, stale replay admission, empty histories, authenticated
schema tampering, and maximum provider positions. Existing primary history contracts and the
application dependency graph are controls. 12,932 local cases were accepted with prior
identities and unchanged dependency closures verified; exact evidence is linked below.

The repository is a structural conditional update, not authority to acknowledge native content.
Pass request/advancement owners, exact attempted-reference evidence, unresolved-outcome policy,
global recovery lanes and retry, primary EOF demotion plus recovery wake, and SDK key-subscription
ownership remain separate work. No incoming producer/collector is activated. Native SDK8 Apple,
full blocked-queue liveness and combined Company/Group native proof remain open. All54 PRD groups
remain open; no Starter or live deployment changed.

[Exact validation evidence](measurements/collaboration-history-recovery-position-2026-11-11.json).
