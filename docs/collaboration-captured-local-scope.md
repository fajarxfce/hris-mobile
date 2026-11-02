# Captured local chat ownership

`CollaborationLocalScope` distinguishes the selected workspace from a captured
local account/company partition. It is separate from the short-lived remote
`CollaborationOperationScope`; a captured partition never grants permission to send,
refresh history, open a runtime or call an HRIS endpoint.

The captured policy checks the active local session, account, API audience and
canonical company identity. It deliberately has no token, parent-expiry or remote
admission deadline. Previously acquired local facts remain available after ordinary
credential expiry. A caller must derive this scope from owned records; raw FCM
company/account fields are not authority. Repository partitioning and retained
company admission gates continue to apply.

Message observation, receipt recovery, receipt-to-command projection, completed
receipt retirement and retained setup/conversation control reads now accept this
scope. Existing String callers delegate to Selected and retain their workspace
permission/module/policy checks. Captured operations do not read or change workspace
selection. Control reads additionally bind the retained command to the current
native family, without requiring fresh credentials. A command from an earlier
native family remains stored when it cannot be exposed by the new one.

The existing local algorithms remain shared. Original receipt recovery retains its
128-command/32-receipt bound. Retirement archives at most32 completed commands before
removing their exact receipts. Cancellation and late owner changes stop subsequent
steps without deleting earlier atomic receipt/completion facts. Captured observation
closes its message subscription on confirmed company denial or account/session loss;
token rotation for the same account does not restart that subscription. Ordinary age
never becomes a confirmed company denial.

[Evidence](measurements/chat-captured-local-scope-2026-11-02.json) records15 new cases:
expired-credential recovery and projection, bounded retirement, retained denials,
late owner/cancellation, atomic receipt/completion retention, local observer lifetime,
and setup/direct/room control binding. These are domain fixture checks and required
repo/browser regressions; they add no actual JNI, FCM or deployed worker acceptance.

Continue local delivery/draft discovery and cursor/queue/confirmation integration,
remote room-control admission, then generalize the existing bounded application
worker. Keep the current/received remote response separate from historical command
failure when retaining denials, so a newly admitted retry does not reclose its gate
merely because an old outcome contains a denial. Initial parent readiness, durable
all-company wake/reconnect/periodic recovery, incoming notification projection and
inline reply remain required. Backend delivery stays disabled until actual receive
acceptance. Phone release14 predates this increment; no new APK installation here.
