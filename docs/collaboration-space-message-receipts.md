# Organization message receipt journal

Status: local source validation accepted. Native delivery and product routing for new
organization messages remain disabled. Existing company receipts retain their format.

A receipt binds one exact account/space message intent to its original physical Matrix
device, SDK transaction, sender, content hash and accepted event aliases. It can confirm
that exact Staged record. It cannot turn Queued, Cancelled or unresolved Staging into an
acknowledgement, change the submitted content, or replace an already retained primary event.
The domain policy owns these rules. Repository contracts expose journal I/O and exact
retirement separately; the owning completion flow must establish retirement evidence.

The encrypted journal uses its own versioned partition and authenticated envelope.
Partitions stay beneath the existing receipt prefix so both old and new key initializers
notice retained ciphertext before generating a key. Both formats share the existing
protected receipt key without reinterpreting a space as a company. Missing keys fail
without overwriting either format, including data owned by an inactive account.

Appending new receipts or event aliases commits the journal and a fresh recovery wake
in the same database transaction. A full work-marker store cannot leave an unobserved
receipt behind. An identical retained append changes neither ciphertext nor the wake,
so replaying the same acknowledgement cannot continually schedule recovery. Alias union
preserves the original primary event. Conflicting content, mapping or origin rejects
the entire append. Inputs are detached before storage suspension.

Each conversation retains at most 128 originals and 512 event aliases; each original has
at most 128 aliases, and each append/retirement contains 1–32 originals. Exhaustion never
evicts an unprocessed receipt. Retirement requires the exact observed receipt and
checkpoint, so concurrent alias arrival prevents stale deletion. An authenticated empty
checkpoint remains after the final removal. Lost responses and cancellation preserve
committed facts for bounded readback and explicit reconciliation.

The shared storage contract runs against actual SQLite and Chromium IndexedDB; an Apple
SQLite wrapper exercises the same contract in hosted validation. Tests include restart,
reauthentication, competing writers, owner replacement, lost responses, cancellation,
bounded capacity, ciphertext/metadata tampering, exact retirement, and both directions
of key-loss protection between company and space journals. Passing source compilation
alone is not Apple runtime acceptance.

Before enabling sends, complete mixed old/new queue matching, explicit new-message
staging, acknowledgement projection and full recovery inventory. The original-only
native lease keeps its existing capabilities. A journal receipt by itself does not
permit sending, history publication, completion retirement or waking an OS worker;
those decisions belong to the admitted owning use case.

Focused validation passes 62 fresh cases: 10 domain policies, 25 actual SQLite cases,
25 actual Chromium cases and 2 application DI cases. Full validation passes 9,488
verified results (7,469 fresh + 2,019 exact accepted predecessor results), with zero
failures, errors or skipped tests. All 4,689 source inputs and dependency files remain
fixed. Root checks,Android host/release compilation/lint,Wasm/iOS source,browser,
proof APK/unit tests and quality pass. All 90 AppFlow cases remain unchanged and pass.
The proof APK was compiled only; native execution and phone installation are separate.
[Accepted local evidence](measurements/collaboration-space-message-receipts-2026-11-10.json).
Pinned Apple execution for the new receipt contract remains required.
