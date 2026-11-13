# Admitted local Group message cancellation

CancelCollaborationSpaceText accepts the caller's exact untouched Queued original under current
local account/Group admission. It checks the retained command, reads repair progress and prepares
a fresh request with the existing pure policy. The repository atomically archives cancellation
and that request; no separate progress write or native operation occurs. A captured repair pass
and its acknowledgement survive the new demand.

An exact cancelled archive confirms success before checkpoint acquisition. Retrying an uncertain
committed response therefore preserves later progress. A missing or changed original is a conflict;
work that began delivery cannot be locally cancelled. Genuine command/checkpoint contention permits
at most three attempts, each rereading the current command. Operational errors and cancellation
stop immediately. Admission is rechecked after every I/O, including an uncooperative late write;
expired credentials and absent selected-company state do not hide owned local facts.

Fourteen new shared methods cover replay, missing/started work, contention, retained pages, malformed
facts, expired identity, denial/replacement at each I/O boundary, lost responses and disposal. The
existing real SQLite application test now invokes this admitted action and verifies inbox removal,
draft preservation and reopen continuity while command IDs are suppressed at the worker boundary.
Focused 17 and full 16,198 pass, preserving all 16,170 prior identities plus 28 JVM/browser executions.
Android compilation/lint, browser execution, Apple source/test compilation and proof assembly pass.
Actual 14 Apple methods require the next source control alongside 43 earlier shared methods.

This cancels materialized local originals only. Composer submission removal, native queue
cancellation, remote deletion, receipt retirement and product interaction retain their separate
contracts. Group receipt retirement, legacy references, incoming history, product ownership,
pending edit references and global clock ordering remain. No phone or live service changes.

Evidence: `docs/measurements/collaboration-space-original-cancellation-2026-11-13.json`.
