# Recipient transfer under local account ownership

An admitted account/Group inbox lifetime now also owns recipient transfer, including Web. The worker uses local source, conversation-catalog and inbox-index observations. Registration starts no work; replacing the account cancels and joins transfer and all three subscriptions through the existing ApplicationCollaborationInboxOwner. Native Matrix readiness is independent.

## Stable source identity

An encrypted `outbox-version:v1` document belongs to the existing recipient partition. The checkpoint format remains unchanged. Explicit Send and conditional original removal advance this marker in the same transaction as their original mutation. Editor-only writes preserve its logical version and existing ciphertext. New editor-only partitions carry an explicit empty version; legacy missing markers use an authenticated checkpoint fallback, and their first editor upgrade preserves that logical token. Exact handoff replay does not rewrite metadata or wake storage.

CollaborationSpaceRecipientSnapshotCodec captures and validates bounded raw working snapshots before key/cipher suspension. It distinguishes legitimate original/editor tombstones from malformed metadata; the source-version marker itself is never a tombstone. Repositories retain raw reads and conditional transaction orchestration. Cryptographic codecs authenticate the checkpoint, source and marker. Missing keys cannot authorize replacing retained ciphertext.

## Finite, dependency-driven transfer

ReadCollaborationSpaceRecipientPending owns current admission/session checks and freezes at most eight immutable originals before the post-read check. Its read has a five-second deadline. Each worker pass has at most sixteen pages under the 128-original quota; invalid cardinality, duplicate IDs and nonprogressing cursors fail instead of looping. One blocked recipient cannot prevent later originals from being visited.

The worker finishes its finite pass when its own source writes arrive and coalesces the latest inputs into a later pass. It does not cancel and restart from the first blocked recipient on each removal. The pure domain recovery policy retains each waiting result against its relevant dependency: missing conversation waits for catalog change, missing inbox acknowledgement waits for index change. Other successful removals do not retry an unchanged blocked recipient. A failed operation requires a changed frame or replaced owner. No retry timer, HTTP call or native client belongs to this worker.

A drained source token stays idle across unrelated catalog/index changes. Attempt memory is bounded to 128 originals and pruned after each complete scan. Existing canonical insertion and inbox seeding remain in their existing use cases/owner; recipient retirement still requires the exact durable destination acknowledgement.

## Validation and rollout

Accepted 18,125 test executions (15,944 fresh and 2,181 exact unchanged), preserving all 18,035 prior case identities and adding 90 executions. Focused 127 and the complete root/Android/browser/Apple-source gates passed. Apple runtime acceptance remains separate. Real-storage tests cover typing, legacy upgrade, lost committed replies, malformed markers, key loss, mutable adapters, cancellation and account/Group isolation. Domain and worker tests cover bounded pages, dependency changes, own-write settling, fairness and account replacement. Actual application DI resolves the new ownership path.

This source integration does not turn on live Group routes. Pending-recipient inbox rows, rapid Send ownership, contact navigation, complete company membership filters and explicit product/live rollout remain work. All 54 communications PRD acceptance groups remain open. Phone release 93 predates this change and remains the delivered loop-fix build. This HRIS policy is not a Starter port.

[Exact validation](measurements/collaboration-recipient-owner-2026-11-14.json).
