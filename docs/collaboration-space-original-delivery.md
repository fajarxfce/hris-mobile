# Owned original queue delivery

Status: accepted owning source contract; focused and full local checks passed. No product
routing, product APK or deployment is claimed here. The subsequent
[actual SDK experiment](collaboration-space-original-delivery-native.md) is accepted separately.
Mobile direct transport is accepted/pushed asab17eadd; backend96d2d238 is accepted
source only. Organization routing and schema remain disabled in the live pilot.

`DeliverCollaborationSpaceOriginalMessages` owns one invocation and one original
physical queue writer. A concurrent invocation returns busy immediately. The flow
reads retained immutable room binding, observes one current native direct transport,
and matches the full original inventory to the logical organization runtime and exact
original physical device, parent, room, sender and immutable application commands.
No room setup, key enrollment, new message or replacement transaction is available.

The owning use case coordinates repository contracts directly. Datasources keep raw
I/O, repositories map/persist, pure policies validate immutable facts, and presentation
receives no SDK writer. Its internal Window lives for one invocation and owns admission,
receipt projection, callbacks and final pause/drain. No nested use case is called.

Preparation is bounded15s. The separate SDK delivery owner may wait25s inside a30s delivery/projection deadline; that wait is
not accidentally covered by the preparation timeout. Native hard expiry, wall rollback,
monotonic time, current directory denial and the exact installed runtime are checked at
suspension boundaries. Confirmed native endpoint denials are retained before cancellation
and are not erased by failed restriction persistence. SDK credential failures do not
pretend to be an organization-wide denial.

Before resuming, read the bounded original journal and all acquired commands. Exact
archive lookups also return previously retired immutable commands. A durable Sent record
plus its journal is valid before archival; later aliases retain the archived primary.
Validate captured originals before any state projection. Staged-to-Sent uses exact CAS
and readback when a response is false/lost, without a second write or a new transaction.
Only proven acknowledgements can settle SDK originals. An empty queue or AlreadyAbsent
never establishes an acknowledgement; missing locally pending originals remain unresolved.

Recipient readiness=false allows acknowledgement repair, then leaves unresolved
originals paused. It starts no timer or transport polling. Delivery resumes only the
exact remaining captured inventory, comparing transaction/content mappings independently
of harmless list reordering. Progress and final acknowledgements append to the
captured original namespace in bounded NonCancellable writes: batches up to32, a5s
journal deadline,128 retained messages and512 aliases. UI/state projection additionally
requires the active original invocation and current admission. Late cancellation cannot
erase an already observed receipt. Final cleanup pauses and drains callbacks before
writer release; a cleanup failure withholds success while prior failure/cancellation wins.

The implementation adds24 owning-flow common test methods plus10 pure policy methods,
keeps existing original queue match/read regressions, and resolves the use case through
actual app DI. Tests cover original-only delivery, pre-archive journal recovery, uncertain
CAS, missing SDK originals, readiness, immutable bounds, cancellation and denial,
late receipts, independent deadlines, exclusive invocation and failed cleanup.
Initial focused verification passed49 cases on fixed sources. Seven review regressions
then ran on unchanged production:24 tests, exactly two failures. They demonstrated
order-sensitive inventory rejection and unbounded post-ACK storage. The correction
changes only the owning use case, compares complete transaction/content maps, and
adds the independent30s delivery/projection deadline. Focused revalidation passes56 cases with fixed source and zero failures/errors/skips.
The full local gate passes4,304 fresh cases:3,629 desktop,621 Android host and54
Chromium cases. All4,571 application inputs and Inspector sources stayed unchanged.
Root, release compilation/lint, Wasm, Linux iOS source/configuration, formatting,
architecture and Detekt pass. The strict source/report audit verifies all six browser
classes and both initial failing assertions. No failures, errors or skips remain.
[Accepted evidence](measurements/collaboration-space-original-delivery-2026-11-09.json).
Actual Android SDK delivery now has separate accepted evidence. The exact69e639f8
[hosted gate](measurements/collaboration-space-original-delivery-hosted-2026-11-09.json)
passes9,137 cases across desktop,Android host,browser and Apple simulator, plus the
unsigned Xcode host build. Actual native Apple Matrix SDK6 delivery remains open.
Private runner/evidence: `.work/space-original-delivery-2026-11-09`.

Still required: automatic original-key publication activation, owned group
readiness-event consumption, background delivery ownership and product routing. Preserve
the eight retained native originals and never-send Draft66. This HRIS policy is not a
Starter port. Rich messages, encrypted recovery, calls and meetings remain in the PRD.
