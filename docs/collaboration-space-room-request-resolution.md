# Owned resolution of original room requests

`ResolveCollaborationSpaceRoomRequest` resolves one retained Room operation under
the current native owner. It returns the original setup receipt or the exact server
non-execution fence after durable capture. Neither result grants room readiness,
content admission, SDK delivery or a message acknowledgement. The use case coordinates
repositories; raw transport, mapping and encrypted storage remain in their existing layers.

## Local facts and current acquisition

The invocation accepts a canonical immutable conversation and the original command ID.
It checks current local account ownership around reads and validates exact API/account/
space/conversation/peer binding. Already Recorded or Fenced results return locally even
when native credentials are old or the directory is denied. Pending is never relabelled
as dispatched or silently cancelled. Archived successor navigation is separate.

For Unconfirmed, preserve the strongest original/directory denial, validate the current
native parent/build and bound acquisition to15s using both wall and monotonic clocks.
Mark uncertainty with the SAME send-attempt count, then read the actual normalized
persisted mark for exact compare-and-set. Resolving an original is not another room send,
so even the maximum1,000 send attempts may still be explicitly resolved. Revalidate
before the one fence request. Keep the original operation, native origin, device and
observed request input; transport uses the current admitted native owner. No old-device
login, read-null inference, request() replay or new operation allocation occurs.

The typed response must match the complete original. Recorded must name its original
conversation and bounded request revision. Fenced must match the exact original and
canonical time constraints. Malformed responses remain uncertainty. A peer denial is
retained for this operation without closing all contacts; global native/account/client
denial restricts the directory and survives interrupted or failed persistence. Positive
setup evidence does not reopen that directory or a denied content projection.

## Captured results and interruption

An observed response gets at most2s of persistence independent of caller cancellation.
Ownership is still checked; no result is published into a replacement account. Native
expiry after an observed response does not erase that immutable fact or authorize new
work. Caller cancellation still propagates after capture. Lost HTTP responses retain
uncertainty and the next explicit call uses the same original descriptor.

A lost local write response can resolve only through an exact retained terminal record.
Competing or different outcomes are never overwritten. The original remains active;
archival, canonical room projection and atomic original/successor handoff are separate.
No automatic loop, hidden repository retry, provider operation or SDK store mutation
is introduced. This use case is bound in the owning data module and resolved by the
actual application container; product routing has not yet been activated.

## Validation

[Exact source and results](measurements/collaboration-space-room-request-resolution-2026-11-07.json):
focused21909 passed91 cases/219.787s on4,405
unchanged inputs. Full28463 passed3,755 fresh cases/460 suites/
525.629s on4,405 unchanged inputs, zero failures/errors/skips/lint.
Root check, Android host/release lint, format/architecture/Detekt,64 explicitly verified
Browser cases and shared Wasm passed. Actual22 resolution and4 resolution-storage cases
ran on Desktop and Browser, alongside existing Open/storage/transport regressions.
All local jobs are reaped; source native/Apple CI remains separate.

The22 domain cases cover terminal offline reads, Pending rejection, current-parent
replacement, maximum attempts, wrong original/peer, lost HTTP/local replies, cancellation,
competing outcomes, owner replacement, peer/global denial, malformed receipts/fences,
wall-clock rollback, monotonic deadline and bounded capture. Four real-storage cases
exercise normalization across directory/command codecs, exact outcome recovery after
an actual committed write with lost response, cancellation with observed receipt and
new local/native parent after database restart. Desktop SQLite and Browser IndexedDB
run those cases; Apple wrappers are included for source CI. Android host regression
and DI are separate from those actual database targets.

No new APK, successor or phone53 latency acceptance is claimed. Earlier Open source
CI does not replace source CI for this new owning flow.
