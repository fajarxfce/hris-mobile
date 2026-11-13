# Encrypted organization drafts and send handoff

Group chats now have separate encrypted editor slots and composer outboxes. The existing
repositories implement both typed contracts and share their raw storage operations. Company
frame bytes and partitions remain unchanged; Group content uses
`collaboration-history/space-composer/v1/{spaceId}` with separate authenticated frames.
An independent legacy ciphertext test checks compatibility without using the new encoder.

A new Group send commits the immutable submission, the exact editor successor and a fresh
recovery wake in one database transaction. Replaying it preserves subsequent typing and does
not rotate the wake. Empty drafts retain their revision to fence stale writes. Outbox capacity
is 128 originals and indexed recovery pages contain at most eight entries. Existing history
key protection covers the partition, while history discovery does not mistake it for messages.

The shared storage contract covers owner isolation, concurrent handoff, interrupted replies,
capacity, indexed reads, exact retirement, cancellation, missing keys and ciphertext transplant.
Application composition resolves Company and Group contracts to the same repository instances.
Admission and delivery policy remain in use cases; storage never sends or provisions a room.

Focused 81 and full 14,863 pass. All 14,828 previous case identities remain with 35 new
executions: 16 JVM, 16 browser and three Android host protocol cases. Android release compile/lint, shared Wasm/iOS compilation, proof assembly
and native test-source compilation pass. The 16 new Apple methods still require hosted
execution. No SDK/proof source changed and no new native scenario or phone test is claimed.

[Evidence](measurements/collaboration-space-composer-storage-2026-11-13.json) retains exact
source/report/APK hashes and the failed method-length candidate. Continue admitted editing,
immutable submission and bounded recovery before activating the Group composer in the UI.
