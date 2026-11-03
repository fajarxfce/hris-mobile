# Durable mute disposition

A retained incoming original that is processed while its conversation is muted must
not alert later merely because notification-hint acknowledgment was interrupted and
the employee subsequently unmutes the conversation.

`PrepareCollaborationMutedNotification` reviews the actual native parent, admitted
company, owned direct conversation, current mute settings and retained original. Its
result carries source IDs, hash, timestamp, settings revision and the exact previous
journal reference. It contains no message text, preview or action envelopes. The
read-only operation has an eight-second cooperative deadline.

Inside the existing notification surface lease, the publisher removes any previous
active native render by its exact notification/render IDs. Only acknowledged removal
or confirmed absence proceeds. A replaced render is preserved. Conditional
reconciliation checks its discovered journal reference before native removal.

`CommitCollaborationMutedNotification` independently rechecks the reviewed source,
actual parent and company admission, then compares and replaces the original journal
record with an inactive `Cancelled` watermark. The native notification ID is retained
when one already exists; a fresh render ID is generated inside the commit operation.
The stored watermark has the real original hash and timestamp and no preview/actions.
A non-waiting mutex and eight-second cooperative deadline bound this operation; the
outer surface lease still bounds the complete publication. No action token is sealed
and no native show occurs for this path.

A confirmed commit returns `SuppressionRecorded`. Priority recovery may then resolve
its exact hint revision. Reconciliation treats the native removal and journal
retirement as complete, without cancelling the obsolete journal version again.
The process marks only this handled source signal; its normal journal scan removes
inactive sources. Muted replay against an inactive watermark is already suppressed;
unmuted replay uses normal issuance and remains unchanged.

The watermark uses the existing deterministic acquired-original ordering of timestamp
and event ID. This is a presentation watermark, not provider causal ordering. An older
candidate cannot replace or remove the current newer render. The same event with
conflicting hash/timestamp fails. A missing original remains pending unless an
owned redaction fact supplies durable terminal evidence. No placeholder hash is
invented. No journal schema or browser storage migration is introduced.

Native removal, local journal commit and hint acknowledgment are separate operations.
A lost response, denial, changed parent/source/settings, cancellation or storage error
never reports confirmed suppression. If the journal commit already happened, its
inactive watermark survives retry and process recreation. If no commit happened,
there is no durable suppression claim; a later retry must obtain current evidence.
Removal uncertainty preserves the active journal for recovery. Source validation
uses existing encrypted history reads, including their normal decryption; mute does
not introduce a second metadata repository or require own-identity acquisition.

Production notification hosts remain unbound and chat push remains disabled. This
increment does not implement focused-route suppression, historical baselines,
authenticated native input callbacks, or notification-specific OS-death acceptance.
Those requirements remain open before activation. Physical phone release19 is
unchanged by these foundation tests.
