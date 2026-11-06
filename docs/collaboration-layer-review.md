# Chat layer ownership review — 6 November 2026

The user explicitly requires clear responsibilities: ViewModels manage screen state
and typed events; use cases orchestrate repositories and business policies;
repositories coordinate datasources; datasources perform raw I/O. This is recorded
in all three repositories' AGENTS.md. Moving misplaced logic into a generic helper
or controller does not correct its ownership.

## Current findings and correction

PrepareCollaborationRoom now owns finite provisioning readiness and explicit retry
preparation through repository contracts. The ViewModel invokes it and maps READY/
WAITING to screen state. The use case checks ownership/admission, pending commands,
current device and exact failed generation, retains an immutable eligible retry,
wakes the worker and bounds polling to twelve reads/two-second intervals. Shared
pure request validation also serves PrepareCollaborationConversation. It never
calls another use case or provisions/sends messages itself.

Nine new domain cases cover cancellation during I/O/delay, account replacement,
retained denial, stale/missing retry generation, uncertain insertion, no implicit
provisioning, foreign commands and the poll limit. Existing ViewModel readiness/
retry cases remain. Focused gate2 passed81; full room-full1 passed5,428 fresh tests
in674.163s with0 failures/errors/skips, lint0 and4,193 unchanged source inputs.
Gate1's81 tests passed but detekt rejected a long invoke; the cohesive polling
step was then factored within the owning domain use case. [Evidence](measurements/collaboration-room-preparation-2026-11-06.json).
The room-preparation drafts HAVE BEEN APPLIED and refined; never reapply them.

ChatThreadHistoryPager also contains original/reference reconciliation policy in
presentation. Move that orchestration into its owning domain use case next. Audit
local outcome acquisition and composer ownership as separate responsibilities;
selection and editor rendering mechanics remain presentation concerns.

## Active work

Mobile feaf9b1a contains the accepted local editor. Contextual copy now recovers
all owned unapplied edit texts in chronological order through normal long-press
selection. Copy checks the captured owner/viewport again before clipboard access,
keeps ordinary drafts and never retires failed commands. Focused copy gate passed
131 tests; the full gate above includes this increment. Three layout/callback
cases passed; no new copy-menu screenshot was recorded.

[First-frame rendering](collaboration-chat-first-frame.md) now retains three idle
room ViewModels under the account/company navigation graph. The domain use case
owns combined local context/history/command publication with fresh access checks.
Android repeated-entry recordings show bubbles during transition, including offline;
visible-room preparation91902546 also shows bubbles during first-entry transitions
across two offline process starts and one online start (three first + three repeated
trials). The bound remains three idle prepared/visited rooms. Final root validation
passed2,280 fresh tests/368.759s after6,938 in the initial full gate; lint has zero issues. Navigation owns cache lifetime; no SDK/Activity or business retry policy
was added to that cache. The history-pager ownership finding above remains open.

The SDK raw point patch is isolated at
.worktrees/matrix-sdk-raw-event, pinned to upstream8de215a0aa0026afa5754ae5f05a6d168d1a1d60.
It returns SDK decryption kind alongside exact bounded raw JSON. Test5 passed
nine cases/32.474s, including actual encrypted custom metadata, missing-key behavior
and SQLite cold-cache reopen without another event request. Tests3/4 each failed
one fixture because the event mock expected the default token; exact synthetic
crypto-client tokens fixed it. No auth expectation was removed. The reviewed
[portable patch](../tool/collaboration-sdk-patches/README.md) and
[test evidence](measurements/collaboration-sdk-raw-event-tests-2026-11-06.json)
are in Git. Android arm64 JNI and paired Kotlin sources are now built and hashed with
NDK28.2.13676358; the AAR and native encrypted edit consumer proof remain pending.
No shipping SDK version changed. See the newest agent-handoff checkpoint.

Platform product10485916 is already deployed after all14 CI jobs passed. Do not
replay the rollout or accepted Forward publications. Phone build40 is unchanged;
announce each future installation and use install-only. Continue the full54-group
PRD after these increments; neither this review nor local edit acceptance closes it.

Stable encrypted history replay now preserves unchanged records and checkpoint
revisions; [validation](collaboration-chat-history-replay.md) includes three
regressions, 2,353 full-gate cases and zero lint issues. This is storage ownership;
ChatThreadHistoryPager orchestration remains a separate pending domain correction.
