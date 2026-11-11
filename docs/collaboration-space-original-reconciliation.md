# Original retained-reference reconciliation

ReconcileCollaborationSpaceOriginalHistoryReferences confirms a finite captured page from already
retained Company event identities. It reads indexed original facts first and replacement facts only
when the original is absent. Matching event ID, sender and timestamp resolve the reference locally;
a confirmed original tombstone may precede its timestamp. A replacement retains its own timestamp
even after redaction. Missing facts remain pending, and mismatched identities reject the whole attempt.
The existing Company reconciliation uses the same pure matching predicates.

The invocation owns a detached page of at most16 references and a five-second local deadline. It
requires original Company progress (provider=null), the same finite wave, nonregressing checkpoint,
registered physical device, exact current Pending task/source, immutable metadata and account/native
family. Known denial wins before reads and after commits. Ordinary credential age does not hide owned
facts. Changed accounts, source, registration, metadata or native family cannot publish completion;
removed/superseded tasks return no work. Cancellation and timeout stop before acknowledgement.

This owner reads retained identities and resolves matching references. It does not read Matrix, create
a client, write message content, move the pending seek, restart provider pagination or retire a queue
task/wake. A false resolve reply is a failure; an uncertain reply does not undo committed references
or infer success. Later replay stays in local reconciliation. Exact batch/seek and queue completion
remain separate owners.

Twenty-three new usecase cases and seven pure policy cases cover identity matching, tombstones,
missing facts, stale progress/frames/devices, ownership changes, independent source position progress,
mutable caller input, finite bounds, denial, lost/false replies, cancellation and timeout. The scoped
fixture records commit attempts; it is not a database/reopen or native proof. Existing actual Company
history, edit, incoming and wave storage controls remain in focused and full checks. Application DI
resolves the new owner. Focused 139 and browser 137 checks pass; full validation
accepts 12,021 with prior case IDs and unchanged dependency closures verified.

Canonical Published LEGACY aliases, exact Original batch/seek/queue completion, bounded native room
inventory, keys-only recovery and native acceptance still precede raw collector/Original producer
activation. Both remain off; all54 PRD groups remain open. No generic Starter change, phone installation,
live deployment or new native SDK8 Apple runtime acceptance is claimed.

[Exact validation evidence](measurements/collaboration-space-original-reconciliation-2026-11-11.json).
