# Owned room lookup for incoming recovery

`RefreshCollaborationList.byRoom` resolves a direct-conversation summary from an
opaque Matrix room hint without scanning the conversation directory, selecting a
workspace, provisioning a room, or starting the SDK. It uses the existing selected
or captured operation scope, current admission, publication mutex and encrypted
list merge. A previously confirmed denial remains closed through failed retries;
only admitted publication can reopen it. Cancellation and late account replacement
cannot publish a successful result.

The native request is `GET companies/{companyId}/collaboration/direct-conversations/
summary-by-room?roomId=...`, encoded by Ktor as a query parameter. The response bound
is 4,096 bytes. Room IDs must start with `!` and contain 2–255 printable ASCII
characters. Repositories validate both requests and returned company/participants;
this operation does not create a command or idempotency key.

Platform companion commit `2124c6fc` resolves the existing participant-owned binding
under current native-session, membership, company and module guards. Its read
transaction checks access again after acquisition, including missing-room outcomes.
Unknown rooms and other participants' bindings do not expose a summary. No new
migration or provider request is needed.

The returned summary is metadata, not a room lease or notification permission. A
push worker must independently obtain fresh admission, read the admitted room
binding, compare its exact Matrix ID to the hint, and own/close its SDK and timeline
leases before processing history. Hint timestamps never authorize content. The
existing fair full-recovery sweep remains necessary for dropped/coalesced pushes.

Tests cover retained denials, cancellation, publication races, expired and replaced
native parents, literal reserved query characters, malformed/oversized/foreign
responses and actual application DI/SQLite persistence. The application test starts
without a selected workspace or cached list, performs exactly one lookup and no SDK
restore/history operation, then restores the session and reads the retained summary
after reopening the application container.

Validation and exact source/report hashes are recorded in
[the measurement](measurements/chat-room-lookup-2026-11-02.json). This increment does
not enable push delivery, display notifications, implement inline replies, deploy
the private backend or replace the phone APK. Those remain subsequent work.
