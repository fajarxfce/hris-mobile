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

The confirmation UI exposes a 48 dp action only on unmatched owned Queued/revision-0
rows. The ViewModel retains the complete selected record and captured access across lost
replies; duplicate confirmations are ignored, and retry uses that same identity even after
the pending row disappears. Changed/started work becomes unavailable. A five-second waiter
deadline retains an unconfirmed result for retry. Close, Back, Stop and disposal cancel the
obsolete waiter; object identity and access reject uncooperative late results. Closing a
dialog does not undo an archive commit. Draft and cancellation modals cannot stack.

[UI evidence](measurements/chat-cancellation-ui-2026-11-01.json) binds 64 cumulative inputs
and 17 changed source files. Full integration passed in 205s Gradle / 208.895s wall with
2,840 desktop cases, 401 domain/263 data/101 presentation browser, 170 Android host and six
proof cleanup cases. Ten cancellation ViewModel cases cover retry, late disposal/scope
results and competing selections; eight thread layout cases pass. Lint has zero fatal/errors
and 47 warnings. The first attempt failed only two test generic-inference expressions; both
were corrected and the failed source/log retained.

A separate 48s / 51.475s desktop/quality pass adds an actual 320 × 640 dialog-window test
without changing production inputs. At 200% font, long preview/failure content scrolls while
Close and Retry stay fixed inside the dialog. Three screenshots were visually inspected:
[queued action](images/chat-thread/chat-cancel-queued-large-text.png),
[regular dialog](images/chat-thread/chat-cancellation-dialog-large-text.png), and
[narrow dialog](images/chat-thread/chat-cancellation-dialog-narrow-large-text.png).
The regular dialog capture is 1024 × 768; the independent narrow capture establishes the
320-pixel boundary. Full and supplemental reports retain their own source attribution.

Next: clean release 10+ installation, then actual native cancellation interoperability,
incoming notifications/replies and all remaining communications work. Release 9 predates
cancellation. No generic Starter policy or database schema change is required.
