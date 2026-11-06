# Chat layer ownership review — 6 November 2026

The user explicitly requires clear responsibilities: ViewModels manage screen state
and typed events; use cases orchestrate repositories and business policies;
repositories coordinate datasources; datasources perform raw I/O. This is recorded
in all three repositories' AGENTS.md. Moving misplaced logic into a generic helper
or controller does not correct its ownership.

## Current findings and correction

ChatThreadViewModel still decides explicit room provisioning retry eligibility and
polls readiness up to twelve times. PrepareCollaborationRoom is being implemented
as the finite domain workflow over repository contracts. It will capture and
recheck ownership/admission, read pending commands, retain an eligible explicit
retry, wake the application worker and bound readiness polling. Shared pure request
validation will also serve PrepareCollaborationConversation. The ViewModel will
invoke this use case and map its outcome to state. No use-case-to-use-case calls;
provisioning and message publication remain with the worker.

The domain implementation is currently an UNAPPLIED .kt.draft under
.work/chat-editing-review-2026-11-05. It still needs review, integration and tests.
Do not report this boundary correction as complete. Cover cancellation during I/O
and delay, late account changes, retained denial, stale device/version, uncertain
insertion, concurrent intent, explicit retry, no direct publication and the poll
limit. The existing room-readiness and retry ViewModel tests remain required.

ChatThreadHistoryPager also contains original/reference reconciliation policy in
presentation. Move that orchestration into its owning domain use case next. Audit
local outcome acquisition and composer ownership as separate responsibilities;
selection and editor rendering mechanics remain presentation concerns.

## Active work

Mobile feaf9b1a is pushed and contains the accepted local editor. The working tree
adds contextual copy of unapplied edit text; editor-copy1 is running. Its source
must remain frozen until the gate drains. Full checks and layout inspection are
still required for this change. The SDK raw point patch is isolated at
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
