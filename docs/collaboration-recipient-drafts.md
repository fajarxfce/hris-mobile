# Durable drafts for a new chat recipient

A recipient working copy stores peerAccountId, its compare-and-set revision, exact text and captured
time before a canonical conversation exists. It is a distinct domain type; peer and setup-command
IDs never stand in for conversation IDs. Empty replacements keep their revision to prevent a late
writer from reviving cleared text. Replaying an exact saved value does not rewrite it.

The repository stores authenticated records in a separate account/Group recipient partition with
the existing protected content key. Checkpoints, record identity, sort and versions are validated;
raw collections are bounded and detached before cipher suspension. Missing keys over existing
records fail without generating a replacement. Shared-partition contention retries at most three
times and never overwrites a changed target. A cancelled operation leaves no follow-up write or
raw reader alive. This is local encryption; no network transmission occurs in these operations.

Use cases own session and durable Group admission. Creating the first draft requires a published
contact. An existing owned working copy stays editable after that contact disappears, without
implying permission to send. Ordinary age, live credentials and HR Company selection do not control
local readability. Confirmed denial and account replacement stop content. Failed retries preserve
a denial until admitted publication. Identity/admission source completion closes the current
reader; repeated equal facts do not restart it. Edits reconcile a lost write response by exact
readback and preserve the original storage failure when success remains unproven.

Twenty-four shared domain cases and thirteen storage-contract methods cover these boundaries.
Storage tests use actual SQLite/IndexedDB adapters and authenticated encryption, including reopen,
competing writers, lost replies, changed frames, cancellation and mutable provider snapshots.
The actual application DI test resolves the new repository and both use cases. Apple wrappers
are source-compiled; this package does not claim new Apple runtime execution. Exact platform
counts, source/report hashes and unsuccessful earlier attempts are in the measurement JSON.

The new-contact screen, inbox display of recipient drafts, peer-addressed original message queue
and canonical handoff remain unimplemented. Persisting a working copy does not implement offline
Send. The next step must preserve multiple explicit sends and their original identities before
canonical setup, then acknowledge destination retention before discarding a peer source. Never
overwrite another canonical draft or remove unconfirmed source text. Keep retry/reconciliation in
domain and screen state in presentation; do not add network calls to input or navigation effects.

Reuse the existing swipe hub and FAB when wiring product entries. Keep Group root lifetime outside
Company navigation and AppViewModel at its window owner. Explicit rollout selection and complete
company membership filtering still remain; null Group root is not proof legacy chat is permitted.
No new APK, phone installation or live rollout is part of this source package.
A read-only ADB check on14 November at14:27 UTC confirms versionCode92 on10.77.77.3:5555;
the app was neither launched nor driven. Phone92, GroupOFF and
undeployed V117–140 remain; all54 communications PRD acceptance groups remain OPEN. This HRIS
capability is not a generic Starter port. Preserve the blocked SQLite fixture and failed Apple proof.

[Exact validation](measurements/collaboration-recipient-drafts-2026-11-14.json).
