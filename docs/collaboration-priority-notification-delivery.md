# Priority message notification delivery

A priority push remains scheduling metadata. Incoming recovery acquires the hinted room and
an exact original or tombstone, retaining self identity and history under its finite runtime.
It captures only company/conversation/room/event IDs. The application returns those IDs only
when the combined recovery result is Complete and after joining the timeline and background
runtime. The IDs grant no publication authority: the notification process and publisher
recheck current parent, company admission and owned source records.

Priority recovery submits that candidate through the single application notification process
before acknowledging the captured hint. The existing20-second outer budget contains both
incoming recovery and the process's8-second caller-owned request; publication is outside the
incoming room's12-second budget. Cancellation still joins owned work/compensation, so these
cooperative deadlines cannot forcibly interrupt an uncooperative native call.

Submitted, policy-suppressed and unchanged originals can resolve the hint. Missing source
history, unavailable/closed host, uncertain storage/native result, permission, disabled channel
or capacity retain it for retry. An expired scheduling hint can complete separately; its
recoverable publication journal remains independent. A clock rollback cannot treat a not-yet-
due hint as expired. A hinted unprovisioned room cannot acknowledge an original it never
acquired. Completing a captured hint uses the existing compare-and-set, so a newer push
revision received during acquisition or native submission survives that acknowledgment.

CollaborationNotificationDelivery accepts ownership of one explicitly supplied Process and
starts it once. Its return value confirms ownership transfer, not readiness. It rejects a
second binding and cannot reopen after close. An unbound or closed delivery never reports
success. Closing rejects new requests and cancels the process; closeAndJoin also waits for
registered work, source watchers, exact native compensation and owner cleanup. A platform host
must join it before closing application storage. Koin's synchronous onClose only starts
cancellation and does not replace that joined lifecycle boundary.

Integration tests use actual Koin, encrypted SQLite, the coordinator and finite publisher with
fictional acquisition and a fake OS. They exercise no-host recovery across reopen, pending
identity, permission/channel/capacity, uncertain submission, cancellation after native show,
initial mute, dismissed-original replay and newer push revisions. The first candidate's fake
submission checks that its acquisition leases have already closed. Independent journal
reconciliation can run while later incoming work owns another transport turn; the test does
not impose a global prohibition on overlapping network activity and native display.

The isolated Android provider proof explicitly starts the same application process and
publisher against a labelled fake display. It checks that the actual provider runtime has
closed before the first fake submission, that the publication is Published before hint
acknowledgment, and that the journal survives reopen. The fake retains only opaque references.
Its output marks priorityNotificationSurface=fake. Existing raw Android notification tests
remain separate; this proof does not establish native notification-input or callback behavior.
The current Platform driver keeps its older priority summary fields, so those new scope flags
must not be inferred from the reduced summary alone.

Production native entry points are not activated by this change. Focus ownership, native
permission/channel recovery wakeups, initial/backfill/dropped-hint disposition, authenticated
callbacks, encrypted input review and notification-specific process-death tests remain next.
In particular, policy suppression without a journal is currently terminal only through the
hint acknowledgment; durable catch-up/suppression disposition needs its own explicit design
before claiming that old suppressed messages can never be reconsidered after an uncertain ACK.
Chat push stays disabled. No product APK or phone installation is performed here.

[Validation](measurements/chat-priority-notification-delivery-2026-11-03.json) records two
passing focused runs and full checks with3,736 desktop,230 Android-host,six proof-host and ten
build-logic cases, plus proof assembly/shared Wasm. No failed attempt. New native execution
and a consolidated full Apple build remain separate acceptance work.
