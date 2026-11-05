# Replacement transport evidence

A provider-accepted edit can arrive after the fifteen-minute display window.
Discarding every trace of that event would prevent a sender from recovering a
lost acknowledgement or finishing its pending command. The encrypted history
therefore retains replacement transport evidence separately from display revisions.
An ineligible edit does not replace the original or become an ordinary message.

Each evidence document retains event, sender, target, server timestamp, canonical
content hash, optional operation ID, opaque original identity and initial
eligibility. It contains no edit body. A keyed fingerprint of the complete typed
input detects conflicting reuse even if a caller supplies the same content hash
with changed text or metadata. Evidence and an admitted revision commit under the
same owned history checkpoint. Missing targets, conflicting identities and races
fail atomically. Replays retain the first immutable evidence.

Event reads use a keyed point lookup. Operation reads use an opaque sender and
operation index, with a 129-record query to reject more than 128 identities instead
of returning partial evidence. Every document/index is authenticated and bound to
the original; the final checkpoint and account must still match. Reads do not
create missing content keys, contact a provider or retire a command themselves.

Current eligibility also accounts for a newly discovered earlier original alias
and original/replacement redaction. An event can remain proven while no longer
being eligible for display. Both redaction orders retain evidence without
restoring text. A proof remains eligible independently of whether a newer valid
revision currently wins the display order.

Provider-reference reconciliation can resolve these exact identities, including
late rejected edits. Existing admitted revisions remain readable through the
legacy replacement-fact path. Original message, quote and read/send receipt APIs
do not treat a known edit as an original, including a redaction that arrived before
its replacement. An edit event also cannot later be ingested as an original.

This storage increment is not edit authoring or completed send recovery. Durable
edit intents, operation-to-SDK mapping, lost-ack recovery, retirement, optimistic
projection, long-press editing and native exchange proof remain separate work.
The pinned SDK's incomplete raw cold-revision acquisition also remains open.

[Local validation](measurements/collaboration-edit-evidence-2026-11-05.json):
78 focused cases and4,995 full cases passed, with zero failures/errors/skips and
Android lint errors. Seven real SQLite and seven IndexedDB evidence cases cover
late acceptance, restart, both redaction orders, conflicting replay, bounded
operation lookup, checkpoint/account changes, cancellation and substituted data.
No edit authoring, new phone build or native exchange is claimed by these checks.
