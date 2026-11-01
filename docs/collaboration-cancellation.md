# Cancelling a queued message

`CancelCollaborationText` accepts the caller's captured local access and complete observed
Queued record. It checks account/company admission before and after storage. Ordinary
credential expiry does not prevent cancellation of owned local work. A confirmed denial,
account replacement or cancelled caller cannot publish success after a late write.

The existing command archive atomically compares the full original record against the
pending row. If cancellation wins, the original ID can never be inserted again. If Staging
wins, cancellation leaves that uncertain SDK work intact. Staging must commit before SDK
enqueue, so losing this comparison prevents enqueue. No raw removal or native queue deletion
is used. Changed or started work returns `client.collaboration_cancellation_unavailable`.

The archive retains the original Queued/revision-0 ciphertext with an encrypted version-1
`CANCELLED` proof. Its distinct `cancellation-v1` purpose binds every raw record field and
ciphertext through authenticated associated data. The existing sent-completion format and
purpose are unchanged. `Cancelled` is an archive-only domain state; ordinary state DTOs and
updates cannot create it. Pending observations exclude archives. Exact lookup authenticates
and returns the original cancelled intent; receipt lookup validates the proof and returns no
delivery receipt. Missing keys cannot be regenerated over retained archives.

Concurrent cancellers and lost archive replies recover the same immutable original decision.
Queue replay of that ID returns Cancelled without scheduling. Stage returns an already
cancelled original before acquiring a native queue. Application draft recovery can confirm
and clear the matching frozen draft after cancellation; it preserves a newer draft and sends
independent queued messages normally. Cancellation never establishes that a remote message
was deleted, nor can it retract uncertain Staging/Staged/Sent work.

[Source and test evidence](measurements/chat-cancellation-policy-2026-11-01.json) binds 56
cumulative inputs and 541 report hashes. Full root/browser/Android/proof/quality checks pass:
2,827 desktop cases, 401 domain/263 data/90 presentation browser cases, 170 Android host and
six proof cleanup cases. Eleven SQLite/IndexedDB contract cases cover both race winners,
restart, lost reply, concurrent cancellation, stale insertion, changed records, key loss,
proof substitution and caller/account cancellation. Nine policy cases and three application
SQLite/DI worker cases pass, plus Stage regressions. Application tests use a fake native
transport; earlier actual SDK/Synapse proofs remain attributed to their original source.

Next: expose an explicit confirmation action only on owned Queued messages, retain the exact
selection through uncertain replies, fence late results after scope changes/disposal, and
verify narrow/large-text controls. Then native cancellation interoperability, incoming
notifications/replies and all remaining communications work. Release 9 predates cancellation.
No generic Starter policy or database schema change is required.
