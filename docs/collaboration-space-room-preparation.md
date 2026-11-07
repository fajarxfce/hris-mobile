# Owned Room request preparation

`PrepareCollaborationSpaceRoomRequest` recovers an active original before making
any native request. Pending, Unconfirmed and Recorded commands return under the
current local account owner even after credentials expire, the native parent changes
or the directory becomes restricted. Returning a retained fact grants no content or
provider access. The complete bounded inventory is detached before another suspension
and validated for duplicate identities/resources and archived records.

## Preparing one intent

A genuinely new request, or one explicit successor of a Fenced original, requires a
currently usable native parent and no retained directory denial. The use case reads
one exact canonical Room view and one current-parent device observation, then rechecks
directory and native admission before allocating an operation ID. The finite operation
uses a 15-second cancellation bound, wall and monotonic clocks, and the earliest
observed parent hard deadline. Same-family renewal cannot extend that deadline.

The device must be active, structurally valid and match the current platform/owner.
An old provisioning expiry is not itself device revocation. Public device metadata
never proves possession of private SDK keys or a current provider session; the server
must repeat provider/device admission when the retained command is dispatched. No
registration, SDK acquisition, room join, send or provider key replacement occurs here.

An explicit retry must exactly match the observed participant account **and** revision
and a Failed/Cancelled job with no published room. The two participants' revisions
are not globally ordered. With no explicit retry, the request preserves that choice:
the server may record the existing status receipt without enqueuing another job. A
published Room view is not converted into a message acknowledgement or readiness claim.

## Original identity and interrupted writes

Only an unlinked, validated Fenced active original can authorize a successor. Its
remote outcome stays unchanged. The repository performs the already-tested atomic
original/archive/replacement transaction; the owning flow recovers the known original
forward link and validates the actual successor's predecessor and immutable resource.
A competing handoff can return its real winner. Recovery follows at most one link and
never allocates another operation or scans old archives inside the attempt. If that
successor has already handed off again, this attempt fails conservatively; a later
explicit preparation reads the current bounded active inventory.

Regular insertion reconciles the exact generated ID and can recover a competing
resource's retained original. Failed/committed/lost/cancelled responses retain their
original identities; a later explicit call recovers the stored work locally. A token
expiring during a successful local write does not hide the now-owned command. Account
or local-login changes after suspended reads/writes reject late results.

Global native rejection remains gated through bounded cancellation-safe restriction,
including failed restriction persistence. `collaboration_peer_unavailable` does not
close unrelated contacts. Preparation never clears a directory denial or silently
retries transport. The use case coordinates repositories and pure policies; DI stays
in the collaboration data module, with an assembled application container check.

## Validation and remaining integration

Focused gate `93110` passed 128 cases in 224.652 seconds, with
4,410 unchanged inputs and zero failures, errors or skips. The compiler's unnecessary
safe call was then removed from the already non-null retry-status check; its original
focused source hashes remain recorded separately. Final full gate `61602` passed
3,829 fresh cases in 462 suites/581.105 seconds on 4,410 unchanged
final inputs, with zero failures, errors, skips or release lint issues. Root check,
Android host/release lint, formatting, architecture, Detekt, shared Wasm and 101 actual
Browser cases passed. All jobs are reaped. [Exact sources and results](measurements/collaboration-space-room-preparation-2026-11-07.json).

The 23 new domain cases cover original/offline recovery, immutable inventory capture,
current device/room and explicit retry coordinates, global/peer denial, every suspended
ownership boundary, wall/monotonic/hard expiry, original links, competing and interrupted
writes, cancellation, capacity and finite disposal. Existing Open/Room and storage
regressions remain in the final gate, and the real application container resolves
this use case through its data-module binding.

The three additional real SQLite/IndexedDB cases combine actual encrypted commands
with the owning use case: original ciphertext and both links survive restart and
expired parent replacement; a committed transaction with a lost return resolves the
same successor; cancellation after actual commit recovers without another HTTP read.
The same contract's four original Room resolution cases remain. Existing Apple test
wrappers inherit the cases; exact new-source Apple CI remains required.

Room dispatch, durable published binding/projection, validation against original LEGACY
metadata and original SDK delivery are subsequent steps. No product caller, new APK or
physical-phone latency acceptance is added by this slice.
