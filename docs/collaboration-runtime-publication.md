# Admitted native runtime publication

Native construction and application ownership are separate steps. A restored SDK
client can finish opening while its caller is still checking the current session,
company or setup-command retirement. Until those checks finish, the repository
reports `client.collaboration_runtime_opening` and excludes that client from entry.
Another caller cannot use it and then lose it to the original caller's cleanup.

`CollaborationRuntimeLease.publish()` is synchronous, repeatable and performs no
network, sync or queue work. Android requires completed acquisition, no requested
shutdown and no retained credential failure. Ensure, Install, Restore and history
reopening call it after their final admission/cancellation checks; installation
also waits for successful command retirement. No suspending work separates
publication from ownership transfer. Failed publication leaves cleanup with the
original caller. Stored identities, commands and acquired content retain their
existing recovery rules.

Publication identifies the application's resource owner. Sending and history
acquisition still require their own current HRIS admission. It does not enroll a
device, start synchronization, activate a pusher or grant background access.
Finite background borrowing and independent foreground retention follow this
change; a borrower must never receive the parent's close handle.

## Validation

[The measurement](measurements/chat-runtime-publication-2026-11-02.json) binds the
source inputs, build and test results. Five added domain cases and strengthened
existing cases cover pending command/room admission, failed publication, cancelled
restoration, late ownership loss and reuse without a second publication. Four
Android host cases exercise the actual lease with inert FFI handles: native
readiness remains private, publication starts no I/O, failed credentials prevent
publication, and pending native drain prevents entry before releasing the store.
Those host cases do not exercise JNI or a provider.

The separate native proof APK does exercise the pinned Matrix SDK against actual
HRIS, PostgreSQL and Synapse on the dedicated emulator. Its direct fixture owners
assert the opening state before explicit publication; normal application worker
and history entry use the owning product use cases. All five local cases pass:
bidirectional encrypted SDK recovery, scheduled ordinary process-death recovery,
queued intent, frozen-only draft and cancelled draft with an independent intent.
The latter three each verify 33 encrypted events and retained older history.

The proof adapter also implements the managed-pusher repository method added in
the earlier client change. The fixture rejects implicit pusher activation. Its
APK assembly and lint are checked explicitly; ordinary root checks alone did not
compile this adapter. CI now pins the Platform revision used by the successful
local run. Hosted execution requires a separate audit.

No incoming FCM, inline reply, background multi-company worker, phone installation,
Apple/web runtime or full PRD acceptance is claimed. The scheduled fixture's
separate retry/cancellation acceptance remains false. Backend push delivery stays
disabled until the receive/recovery path is implemented and validated.
