# Company thread publication lifetime

A slow collector could receive an already verified thread snapshot after Company
admission was denied. The old flow had a default buffer after admission switching
and another rendezvous outside the owning flow. Cancelling its stored-data readers
could not withdraw the snapshot already held by that outer coroutine.

`ObserveCollaborationThread` now places the rendezvous directly after the admission
`flatMapLatest` and emits to its caller without an additional outer channel. A denied
admission cancels and joins its reader before the caller can accept another snapshot.
Retained history and pending commands stay in their stores. Current ownership and
admission still guard publication after suspended reads.

Context, indexed history and pending-command observation still start in parallel.
Optional recipient metadata still cannot delay the first message window. Existing
scope, cancellation, expired-credential, immutable-snapshot and admission-recovery
controls remain in the same test class.

The new slow-consumer regression first failed on the original production code:
16 cases ran,15 passed and the new case received three values instead of two. Both
readers had retired, but their previously verified private value remained queued.
After moving the boundary, the unchanged regression and all15 existing controls pass.
Full platform validation accepts14,688 cases:12,650 freshly executed and2,038 unchanged
reports, with all prior case identities retained. Android release compile/lint, shared
Wasm/iOS ARM64 compilation and source checks pass. All10 current-source SDK8 native scenarios also pass on a fresh isolated
2560MiB/two-core emulator, including account replacement, receipts, original delivery,
late keys and offline history. No compiler ran alongside that emulator. [Evidence](measurements/collaboration-thread-publication-lifetime-2026-11-12.json)
records the exact source/report digests. Failing inputs and logs remain in
`.work/company-thread-publication-2026-11-12/failed-publication-boundary-1`.

This change belongs to the existing HRIS Company observer. It adds no datasource,
repository, ViewModel state or shared helper. Native SDK and storage formats are
unchanged. The separate canonical organization history uses its already accepted
ownership boundary.

Hosted control run43297910558 validates exact Mobile source151438b4 independently.
Its portable job passes12,666 cases:6,058 desktop,955 Android host and5,653 browser.
The artifact digest, ZIP integrity, full case inventory and prior test identities are
verified with zero replacements or browser-name normalization. Apple job131478499227
is still running; its expected5,187 cases are not yet accepted. Compact artifact,
report and job-log digests are included in the existing measurement.
