# Durable organization recovery wake

Organization readiness now has its own durable scheduling key,
`hris.collaboration-space-recovery`. The repository uses the existing raw
WorkWakeStore; each request retains a new random generation before scheduling.
Acknowledgement compares that exact generation, so completion of older work cannot
clear a newer request. This global record contains scheduling metadata only. It
neither identifies an account nor grants access to SDK state or message contents.
The company recovery key remains independent.

QueueCollaborationSpaceReadinessSignal owns the receipt-to-scheduler handoff. It
captures the local native parent, validates the trusted logical source, and checks
that exact owner/device/bootstrap operation against the local runtime registry.
Checks surround suspended reads and writes, outside native callback monitors.
Credential age alone does not invalidate owned local facts; this flow acquires no
provider credentials and grants no content permission.

The receiver first retains the monotone encrypted pending publication revision,
then requests durable group work and the operating-system schedule. Only after
successful scheduling does it acknowledge the captured publication revision. A
newer pending revision survives that acknowledgement. Each receipt CAS is bounded
to three attempts, scheduling to 1.5 seconds, and the whole handoff to three seconds.
Cancellation propagates. Failed storage, scheduling, current-parent checks or runtime
retirement leave recoverable facts in place; no immediate retry loop is installed.
The application must own one serial collector and its cancellation lifetime.

The new wake repository is registered in the owning data module; it does not call
another repository or create a content key. Its bounded raw key inventory reports
capacity failure instead of spinning. Existing keys can advance at capacity.

Sixteen domain cases cover duplicate/reordered revisions, receiver recreation,
interrupted writes, scheduling errors and deadlines, parent/runtime replacement,
late higher revisions and bounded CAS. Nine shared storage cases use actual SQLite
or IndexedDB: reopen, exact acknowledgement, independent company/group keys,
concurrent generations, cancellation after actual commits, key capacity, malformed
raw readback and delayed old acknowledgement. Apple Room has a source binding;
Apple execution evidence is recorded separately when actually run.

The application does not yet collect this flow and no organization recovery worker
is bound. Scheduling alone cannot discharge original messages. A real group worker,
bounded local work inventory, currently admitted foreground sync on the same native
client, and product routing remain required. No SDK, APK, phone or live rollout is
included in this increment.

Focused validation passes 44 cases in 237.198 seconds. Required full validation
passes 4,338 fresh cases in 593.165 seconds: 3,670 desktop, 626 Android host and
42 forced browser cases, with zero failures, errors or skips. Root check, Android
release compilation/lint, shared Wasm/iOS source and quality checks pass. Both gates
retain the same 4,593 source hashes; Inspector inputs are unchanged. Every current
source, library input and fresh XML hash/count is audited.
[Retained evidence](measurements/collaboration-space-readiness-wake-2026-11-09.json).
