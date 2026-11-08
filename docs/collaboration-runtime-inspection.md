# Coordinating private-key inspection and SDK startup

Native release75 created its first device and confirmed public keys after normal
login/company selection. Its first cold restart then captured
`CollaborationSdkStoreBusyException` in `FileCollaborationSdkStoreSource.acquireFiles`.
[The failed native gate](measurements/collaboration-automatic-activation-native75-2026-11-08.json)
preserves the original observations. An additional successful restart cannot clear
that failure. The earlier release71 Storage exception has a different, unresolved root.

Activation previously checked whether a runtime was open, then inspected inventory
and private-key possession using the filesystem owner lock. Background recovery could
reserve a runtime between those operations. Its registry did not know about the
inspection's lock. Both entry points were individually scoped, but their overlapping
physical resource ownership was not coordinated.

The candidate adds an explicit runtime-inspection reservation. Under the same short
registry monitor as foreground/background startup, it either retains an available
runtime or reserves the physical namespace without opening an SDK. The use case owns
that reservation through both repository reads and their final scope checks, then
releases it before restoration or bootstrap. Inventory and possession repositories
still own their existing raw datasource reads; authorization and key-loss decisions
remain in the domain workflow. The ViewModel has no new orchestration.

Pending or invalidated inspection remains reserved until its actual owner releases
it. Parent/company changes cannot release an unfinished reader's capacity early.
Late results are checked against both current authority and reservation ownership.
An available runtime is retained atomically instead of inspecting its open database.
This operation neither drains that client nor creates replacement private keys.
Raw store-lock failures remain failures; no exception suppression or timer is added.

The Android runtime and domain share a factual resource contract. Unsupported hosts
reject inspection by default. Group original-device inspection/handover remains a
separate explicit workflow; this is not native Apple or cloud recovery acceptance.

Targeted tests cover competing inspection/runtime entry, published-background
promotion, invalidation and exact release, cancellation during private reads, company
loss, assembled foreground/background rejection before file access, and preservation
of the occupied store's missing-key rejection. Existing setup UI fixtures need the
same explicit reservation contract. The source gates pass; retained-device76 proof
remains pending; do not install rejected native75 or port the generic worker yet.

[Focused validation](measurements/collaboration-runtime-inspection-2026-11-08.json)
passes139 fresh tests with zero failures/errors/skips in482.119seconds. All4,503
application/config and82 library inputs stayed fixed. The first run was stopped
after obsolete fake contracts caused UI barrier waits; its failed/interrupted
results are retained. Only four test files changed before the passing run. Domain,
Android runtime/registry, setup UI, application ownership/bindings, formatting,
74-module architecture and detekt checks passed. The full gate subsequently passed5,836 fresh tests/zero failures/errors/skips in
927.610seconds with unchanged4,503 application/config and82 library inputs.
Android compilation/lint, actual browser, shared Wasm/iOS source compilation and
quality checks passed. Native76 validation remains pending. No phone update or generic Starter port is accepted by this checkpoint.
