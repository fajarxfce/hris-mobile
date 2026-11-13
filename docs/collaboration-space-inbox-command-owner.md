# Retained-original seeding in the existing inbox owner

The existing CollaborationSpaceInboxRepairWorker now invokes the admitted single-original seed
from its existing command inventory. A pure domain policy selects new IDs and just-observed
retirements. Normal delivery progress does not repeatedly probe already known originals. Both
snapshots are validated and capped at 128 records, so all at most 256 distinct selected intents
remain available without truncation. Edit operations stay separate.

The worker captures observed input before I/O. A seed-generated repair request can process the
already retained reference, but cannot trigger another seed attempt with unchanged command facts.
The existing 30-second turn bounds work and disposal cancels and joins its subscriptions. A failed
or timed-out seed leaves recovery pending. The latest actual seed failure survives a successful
request-only reference pass. A new operation failure is propagated directly; a later seed denial
cannot be hidden by an earlier storage error. A subsequent external original change retries all current originals
plus newly observed retirements; an explicit owner restart captures current originals again.
Unattempted commands remain in their source. Successful completion of the selected seed actions
clears the failure; there is no new command queue, timer or per-conversation subscription.

Eight new pure-policy and nine shared worker methods cover selection, bounded snapshots, own
requests, normal progress, partial failure, uncertain replies, timeout, disposal and explicit
restart. Existing terminal-repair behavior and all earlier methods remain. A new real application
SQLite case inserts a raw queued original, starts the actual registered owner, observes its pending
inbox preview, cancels the original, then verifies reference cleanup, draft preservation and
reopening. No composer replay, native child or transport send is needed.

Focused 41 and full 16,351 pass locally, preserving all 16,316 preceding identities plus 34 shared
JVM/browser executions and one new desktop case. The hosted desktop selector includes the new
shared worker suite. Android compilation/lint, browser execution, Apple source/test compilation
and proof assembly pass; actual 17 new Apple method executions remain a later gate.

The factory still requires an explicit product account/Group owner. Incoming-history publication,
actual Company-original provenance, pending edits and backward-clock ordering remain to integrate.
An archive missed before observation is not discovered by an active-command inventory; its actual
history producer remains required before product activation. No live rollout, product collector
or phone installation is included. HRIS policy stays in Mobile; shared raw storage is already in
Starter.

Evidence: `docs/measurements/collaboration-space-inbox-command-owner-2026-11-13.json`.

Actual Apple execution is accepted in control 43467846733, exact source `951c308dce2dd350a901c67cf449d6913b97cacf`: 5,980 Apple / 20,097 combined cases, all previous identities, iOS host and 16 push fields pass. This includes the 30 owner/source-guard methods; later history and association increments remain separate. Evidence: `docs/measurements/collaboration-owner-source-guards-hosted-2026-11-13.json`.
