# Shared background runtime acquisition

The application intentionally runs device synchronization and incoming-message
recovery together. Both resolve the same single `AcquireCollaborationBackgroundRuntime`.
Its previous `tryLock` rejected a healthy overlapping lane as unavailable. Recovery
then repeated setup and room work, even though another lane was opening the same
owned runtime. Real use-case tests reproduce that failure; native80 also retains
repeated cold recovery, whose final native acceptance remains open.

The use case now waits cancellably within the smaller remaining wall-clock and
monotonic admission budget. After obtaining the lock it revalidates session and
local access before any runtime I/O. Expiration or cancellation does not call the
repository, and a rejected account cannot inherit another lane's reference.
Repository/native ownership remains unchanged; each admitted caller obtains its
own finite reference and closes it through its existing owner.

Only lock waiting is wrapped by the timeout. A separate regression proved that
timing the whole acquisition could discard a resource after publication. The lock
owner is tracked through timeout/cancellation and always released in `finally`;
resource acquisition uses its existing protected handover and native deadline.

All106 focused tests pass, including14 domain cases, actual registry/Android
identity contracts and company/recovery workers. Coverage includes contention,
waiting cancellation/expiry, account loss, late publication and existing denial/
cleanup rules. Formatting, architecture and detekt pass. Earlier failed tests and
the first formatting failure are retained in [evidence](measurements/collaboration-background-runtime-contention-2026-11-09.json).
Full application validation also passes on the unchanged source:4,261 fresh
tests (3,519 desktop,592 Android host,150 actual browser), Android release/proof
compilation and lint, shared Wasm/iOSArm64 source compilation and all quality checks.
This full run still uses SDK5; final Android consumer checks and native proof with
the new SDK remain separate. Physical iOS runtime was not tested. No phone installation or whole collaboration-feature acceptance is claimed.
