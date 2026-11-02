# Captured local delivery and draft recovery

Delivery and prepared-draft discovery accept explicit `CollaborationLocalScope`.
Their work records carry that scope and the actual local access identity. No
workspace identifier is invented for a captured account/company. Existing
no-argument discovery and String entry points preserve selected-company behavior.

The same local algorithms now operate without workspace selection: delivery cursor
advance, prepared-draft discovery/advance, queue admission, exact-draft confirmation
and review holds. Active local session/account/audience checks and retained company
denials still fence each suspension. Remote credential age does not hide owned
facts. These local scopes cannot be passed as remote authorization.

Delivery retains its 128-record bound, stable conversation order and cursor CAS.
Prepared draft discovery retains its eight-item indexed page and at most one wrap.
The cursor advances before an attempt so interrupted or blocked rooms cannot
monopolize subsequent work. Neither discovery performs network or SDK acquisition.

A lost queue response retains the frozen body and original submission ID. Recovery
finds the committed command and acknowledges that exact draft without inserting a
replacement. A concurrent editor keeps its new slot. An unavailable conversation
can retain the original as requiring review; no command is silently cancelled.
Cancellation after an atomic cursor or draft acknowledgment preserves that fact
while preventing a late publication. Scheduling continues to carry only the
existing task key, never message text or credentials.

`CollaborationDraftRecovery` shares its bounded loop with a captured entry point.
The delivery worker's inner room processing now receives separate local and remote
scopes. Its scheduled entry is still selected-company-only at this checkpoint;
background runtime borrowing is the next connection. Background history focus
capacity cannot invoke foreground reclamation through the shared inner path.

`ObserveCollaborationAccess` reuses the existing local scope observation policy.
Token rotation preserves equivalent local access; replacement or retained denial
stops it. Disposing the consumer disposes its observations.

[Validation](measurements/chat-captured-recovery-2026-11-02.json) includes domain
cases for expiry, cursor fairness/CAS, lost replies, concurrent editors, review
holds, cancellation and access observation. Actual application tests recover an
original encrypted draft after container restart with workspace reads forbidden,
and keep a denied encrypted draft intact. The original selected worker regressions
also run. This is not new JNI, FCM or physical-device acceptance.

Continue the shared worker with a finite admitted background reference, then the
application-parent readiness and durable all-company receive/recovery coordinator.
Backend delivery remains disabled until notification privacy/read/mute, replay and
inline-reply acceptance. No PRD group is marked complete by this scope extension.
