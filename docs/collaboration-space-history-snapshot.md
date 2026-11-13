# Coherent retained Group history snapshots

An incoming event can already be retained, or refer to an earlier/rejected edit. A preview must
therefore read the current original from coherent retained history, not copy the latest transport
body or skip publication on replay. CollaborationSpaceHistorySnapshotRepository resolves 1..16
distinct event IDs under one exact published source revision. It preserves input order and each
canonical original, including aliases, current edits, local hiding and provider redaction.
Absent and tombstone-before-original references have no fabricated original. An absent partition
needs no content key; existing ciphertext with a missing key cannot create a replacement.

The existing encrypted history repository implements this read contract. Its bounded indexed
reader uses at most three working-document batches (64 IDs each), checks their exact checkpoint,
and authenticates aliases, originals, edit evidence and legacy revisions. Shared edit-evidence
mapping preserves the previous target/alias validation without a second implementation. A changed
source returns null for the owning use case to reobserve; the repository performs no retry.
Operational/cipher failures remain failures and cancellation never publishes a late result.
No history scan, network read, source revision write or new lifecycle owner is introduced.

Seventeen new shared actual-storage methods verify bounded alias acquisition, current/rejected
edits, hidden/redacted originals, missing/tampered facts, legacy revision authentication,
source/absence races, account and Company isolation, immutable input, missing keys, cancellation
and reopen/replay. Existing history/edit/evidence methods remain and application binding proves
one shared history instance. Focused 60/full 16,411 pass on the local targets, preserving all
16,377 preceding cases plus 34 new JVM/browser executions. Android, browser, Apple source/test
compilation and the application proof build pass. Actual new Apple execution remains separate.
Two initial test-only tombstone fixtures were corrected after raw storage rejected their tags;
production logic did not change in that correction. The failed evidence is preserved.

This source read is not yet the admitted inbox producer. Conditional source-check/publication,
preview/source policy, exact original Group/Company provenance and existing incoming-owner
integration follow. No product graph activation, native transport, server or phone change.

Evidence: `docs/measurements/collaboration-space-history-snapshot-2026-11-13.json`.

Actual Apple execution is accepted in control 43486239620, exact source `7fedb33ef8c215838dcee9fbc915af25d862f5e1`: 6,039 Apple /20,274 combined cases, all prior identities, iOS host and16 push fields pass. This source covers snapshot/publication/association/Company storage; later canonical source policy remains separate. Evidence: `docs/measurements/collaboration-canonical-history-hosted-2026-11-13.json`.
