# Admitted background conversation control

`LoadCollaborationSetup`, `PrepareCollaborationConversation`,
`ReplayCollaborationConversation` and `ReconcileCollaborationConversation` accept
Selected or Background remote scope. Their String entry points preserve selected
workspace behavior. Background capture requires the current native parent and the
original finite admission; it never reads or changes workspace selection.

Room requests must name the exact device in that admission, including retained
commands before replay or retirement. Loading setup can reopen a retained local
company gate only after receiving the matching active device, matching platform
and a version at least as new as the admission observation. A missing, inactive,
older or different device cannot clear the gate. The existing gate revision CAS
prevents a concurrent denial from being overwritten.

Preparation, replay and retirement share their existing immutable operation ID,
receipt/version and storage CAS rules. They check the retained company gate before
command reads and after suspending work. Late expiry, native-parent replacement
or cancellation prevents publication; an already committed intent remains local
for recovery with its original ID. Failed or pending network work cannot erase it.

Failure precedence and new denial evidence are separate. An old command may
retain a company denial as its explanatory failure even after fresh setup has
readmitted access. Only a newly received HRIS company/session/MFA denial restricts
the company again. Reusing the historical failure as a new denial would otherwise
make an explicitly readmitted retry fail indefinitely. A recipient-specific
rejection does not close all company history.

New denial retention runs inside a bounded cancellation-independent cleanup before
continuation. It remains authoritative when the subsequent owner read or cleanup
fails. No new unbounded retry, room creation policy or native runtime is introduced.

Validation and exact checked inputs are recorded in
[the measurement](measurements/chat-background-control-2026-11-02.json).
The new cases cover readmission, device mismatch/version/state/platform, gate CAS,
original-ID retry, historical versus new denial, expiry, replacement, cancellation
and committed-intent retention. Domain fixtures and proof-adapter compilation do
not constitute new provider or physical-device acceptance.

Next, carry captured local scope through delivery discovery/cursors and frozen
draft recovery, then generalize the existing bounded application worker using
separate local and remote scopes. Background references must not call foreground
runtime acquisition or history reclamation. Durable all-company wake/reconnect/
periodic recovery, notification privacy/read/mute and inline replies remain open.
Backend chat delivery stays disabled pending real receive/recovery acceptance.
