# Conditional retained history inbox publication

A retained source can change after a preview is prepared. publishInbox on the existing history
snapshot repository authenticates the captured source revision and the destination index, then
pairs ReplicaMutation.Check with the prepared inbox mutation in one raw transaction. A source
or index conflict returns false for the domain owner to reacquire; storage failures are not
retried here. The source checkpoint, ciphertext and revision remain unchanged.

The source must already exist and be published. Its retained key is reused by both history and
inbox codecs under separate authenticated namespaces; a missing key cannot create a replacement.
The method freezes caller activities before suspension, verifies conversation/room coordinates,
and rejects draft and repair-reference writes. The destination codec preserves existing anchors,
independent drafts and ordinal allocation. A semantic replay establishes unchanged source/index
observations without calling mutate or manufacturing an empty commit.

Sixteen new shared actual-storage methods cover visible publication/reopen, byte-preserving
replay, source changes before/during commit, concurrent destination/draft writes, stable hidden/
edited anchors, wrong room/owner, missing source/key, lost replies, cancellation/join, source and
index changes during replay, immutable input, forbidden draft/cross-conversation writes, account
replacement and one-attempt storage failure. Existing snapshot and inbox contracts remain.
Focused 50/full 16,443 pass locally, preserving all 16,411 prior cases plus 32 new JVM/browser
executions. Android, browser, Apple source/test compilation and the proof application pass.
Actual new Apple execution remains separate from those compilation results.

This is the conditional I/O boundary, not the admitted incoming producer. Domain source/preview
policy still needs exact original Group/Company provenance, retained-reference replay handling,
local admission around I/O and bounded contention. No new polling queue, product collector,
native send, phone installation or backend rollout is introduced.

Evidence: `docs/measurements/collaboration-space-history-inbox-publication-2026-11-13.json`.

Actual Apple execution is accepted in control 43486239620, exact source `7fedb33ef8c215838dcee9fbc915af25d862f5e1`: 6,039 Apple /20,274 combined cases, all prior identities, iOS host and16 push fields pass. This source covers snapshot/publication/association/Company storage; later canonical source policy remains separate. Evidence: `docs/measurements/collaboration-canonical-history-hosted-2026-11-13.json`.
