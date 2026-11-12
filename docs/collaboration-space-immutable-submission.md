# Preserve the original organization message submission

The local text queue now accepts the existing immutable message intent. Its caller captures
ID, owner, conversation, time, text and quote before suspension or optimistic presentation.
Previously the queue allocated time after waiting for its mutex and local admission/metadata
reads. A retry now has to match the complete original, including its timestamp.

QueueCollaborationSpaceText no longer depends on a clock. Existing Group/session checks,
exact lost-response readback, cancellation and the bounded scheduling hint remain. The
command schema and encrypted message data are unchanged. Forward/edit variants stay outside
this text operation. No transport retry or native admission was added to local persistence.

Both native proof send sites capture one explicit intent before entering the adapter, and
assert exact retention. Six new shared domain methods cover delayed reads and mutex waits,
changed timestamps, wrong readback, committed cancellation, renewed login and invalid input.
All previous assertions and case identities remain.

Focused 59 and full 14,828 pass. The full gate includes 12,790 fresh executions and
2,038 exact unchanged cases, preserving all 14,816 previous IDs with
12 new JVM/browser cases. Android release compilation/lint, proof assembly, shared Wasm/iOS
compilation and native test-source compilation pass.

Two existing affected native Original/Space delivery scenarios also execute against the
exact proof APK on a fresh isolated 2560 MiB, two-core emulator. They retain original provider
transactions across process death, lost acknowledgement/projection and an offline recipient.
No compiler runs beside that emulator; it is stopped afterward. The eight other previously
accepted native scenarios remain historical evidence. Linux executes zero Apple cases.

[Evidence](measurements/collaboration-space-immutable-submission-2026-11-12.json) records exact
source/report/APK hashes and failed candidates, if any. This change does not claim new phone
installation, UI latency or live Group rollout. Continue explicit Group draft/outbox storage,
finite recovery and the [organization UI](collaboration-organization-ui-plan.md).
