# Preparing exact notification publications

`IssueCollaborationMessageNotification` retains an owned notification render before
native submission. It accepts a captured native scope and the bounded message from
notification projection. It captures the actual session parent independently, rejects
stale scope/candidate shapes, and runs under one nonwaiting mutex with a ten-second
budget. No selected workspace, network acquisition or credential renewal is required
for owned local facts. This result is not a native display lease.

## Source validation and replay

An internal authority helper checks the current native parent and retained company
admission after each repository operation. It reads the acquired direct conversation,
notification settings, own provider identity, original message and all original read
aliases. The prepared projection must exactly match the reconstructed bounded source,
settings version, read version and selected preview. Muted, read, self-authored,
redacted, missing or changed sources cannot become new publications. Source, settings,
peer, identity and read snapshots are rechecked; a confirmed company denial wins over
a concurrent storage/session-read failure.

The issuer reads the exact encrypted journal snapshot. For an unchanged active render
it authenticates the four stored envelopes, their action kinds, distinct operation IDs,
target and expected mute preference, and reuses them unchanged. It rereads the exact
journal before returning that snapshot. For a new render it seals and verifies four
fresh action operations, preserves the conversation's stable native card ID and writes
one Prepared record by exact CAS. It checks source and ownership before and after the
write. A lost response or cancellation after commit leaves the original Prepared
record available for revalidation; the caller must not assume the failed invocation
did not commit. Capacity/contention return a failure without eviction or a hidden retry.

## Candidate ordering

Replacement uses acquired original `(timestamp, primaryEventId)` as a deterministic
presentation watermark. It does not claim causal or provider stream order. An older
candidate cannot replace a newer card. The same source with different content hash or
timestamp is a conflict; a different retained room binding is also a conflict. An
inactive watermark suppresses the same source even after reauthentication. An active
same-source render may be replaced when its current preview/source stamps or native
family differ, using the same card ID and fresh render/action IDs.

The application still owns candidate discovery. Initial synchronization and history
backfill must not create a notification flood, and an arbitrary push packet is not an
authoritative message source. A suppressed unrelated candidate must not cancel the
current different card. These source-selection and native cancellation rules are not
implemented by issuance alone.

## Native boundary and validation limits

A Ready outcome is a point-in-time local result. The future native coordinator must
revalidate the exact snapshot immediately before publication under a serialized owner
lease. It must cancel and join obsolete native work when the account, company gate or
privacy state changes. Post-I/O domain guards alone cannot close the final validation
to OS submission race. Published acknowledgments and dismissal/cancellation must target
an exact render and never clear a newer one. Cleanup of an already displayed private
card must remain possible after company denial without granting a content read.

The shared preview factory also serves the existing preparation use case. Its behavior
is covered by the existing eleven projection cases. Sixteen domain issuance cases cover
source changes, deterministic ordering, uncertain writes, exact envelope reuse, bounds,
late denial/family/privacy changes, contention, deadlines and joined cancellation.
Three integration cases use the actual application graph, SQLite, action cipher and
retained-reply submission flow with fictional HTTP and a simulated provider. Credentials
remain in memory across reopen; this is not notification-specific OS process-death proof.

[Recorded validation](measurements/chat-notification-issuance-2026-11-02.json) distinguishes
failed fixture assertions from accepted results. The initial integration assertion
incorrectly expected the original failure display text to survive admission storage;
that storage intentionally retains only kind and code and reconstructs its message.
The corrected assertion checks the stable denial before and after application reopen
and verifies the private journal is retained. Production denial behavior was unchanged.

Native adapters/receivers, explicit retained-input review UI and notification-specific
process-death acceptance remain in the [publication plan](collaboration-notification-publication-plan.md).
Chat push remains off. No Android/iOS notification, voice/video or meeting UI is
activated by this increment, and there is no generic Starter port for HRIS policy.
