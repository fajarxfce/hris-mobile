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

[First-frame investigation](collaboration-chat-first-frame.md) reproduced a367–533ms
header-to-bubble interval in release40, including offline. Proposed combined local
thread acquisition is still UNAPPLIED/UNCOMPILED .kt.draft work. Apply/review/test,
then measure the resulting Android build before claiming any speed improvement.

The SDK raw point patch is isolated at
.worktrees/matrix-sdk-raw-event, pinned to upstream8de215a0aa0026afa5754ae5f05a6d168d1a1d60.
It now returns SDK decryption kind alongside exact bounded raw JSON. Test2 passed
7 structure/cache/network/cancellation tests in34.692s; this does not establish
E2EE exchange, missing-key recovery or reopen acceptance. Test1 failed two fixture
compile errors, subsequently fixed. No SDK artifact/version was published or
consumed. Rust1.96.0 and GCC/make are installed; the broader package transaction
failed on mirror404s without changing packages. NDK is still absent.

Platform product10485916 is already deployed after all14 CI jobs passed. Do not
replay the rollout or accepted Forward publications. Phone build40 is unchanged;
announce each future installation and use install-only. Continue the full54-group
PRD after these increments; neither this review nor local edit acceptance closes it.
