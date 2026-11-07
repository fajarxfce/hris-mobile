# Original runtime transfer owning flow

`AdvanceCollaborationSpaceRuntimeTransfer` coordinates the existing repositories
for one explicit attempt with the original native parent and a fixed15-second
lifetime. It reads the retained registered command and original local handover
intent, then current target-device status. A different or revoked native family
requires the separate recovery protocol; no source binding is rewritten.

For a provisioning target, a fresh provider-key observation precedes private-store
staging. The existing runtime repository excludes acquisitions, stops/joins the
exact original SDK, derives the real private identity under its exclusive directory
lease and durably retains one target. Opening another private reader before this
stage would contend with an already-open SDK. No freeze follows a failed, cancelled
or mismatched stage. Current account/native ownership and any newly observed denial
are rechecked after each suspension boundary.

The entire publication request and staging duration is conservatively charged against
the server's bounded observation interval using monotonic elapsed time. Device and
server wall clocks are not compared for this interval. The original attempt's local
wall-clock/native deadline and monotonic lifetime still apply; refresh cannot extend
them. Before freeze, the publication interval must remain valid.

The original freeze operation is already stored before HTTP. A lost response keeps
that same target and operation for the next explicit invocation. One freeze and one
bounded drain observation are made; there is no polling loop or hidden queue reset.
Zero or nonzero provider slots are returned as AwaitingProvisioning metadata. Only
the backend's admitted worker may publish completion. An already-active exact target
with the retained original intent returns Provisioned metadata for the later bootstrap
and adoption workflow; it cannot grant permission to open an SDK.

Pure policies validate exact original target, publication interval and bounded drain
metadata. The use case owns orchestration and recovery; repositories retain their
raw-I/O coordination responsibilities. DI stays in the collaboration data module.
No ViewModel, screen, platform queue, key upload or provider enrollment is added.

Fourteen domain cases and two actual application DI cases passed in178.895seconds.
The complete gate passed5,552fresh cases in646suites in585.003seconds, with4,276
unchanged inputs and zero failures/errors/skips/Android lint/Detekt findings.
[Accepted evidence](measurements/collaboration-runtime-transfer-flow-2026-11-07.json).
They cover operation reuse, cancellation, late results, changed account/native parent,
new and retained denials, failed denial persistence, publication timing and provenance,
wrong receipts, bounded slots and completed metadata. Group endpoints remain disabled;
no live migration, original-parent replacement, product adoption or new native encrypted
send is claimed by this increment.
