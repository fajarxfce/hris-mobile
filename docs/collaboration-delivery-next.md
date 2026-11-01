# Next concrete increment: platform scheduled delivery

Use the existing SyncTask / SyncScheduleRepository / Android WorkManager integration.
Android scheduler already persists task-key-only APPEND_OR_REPLACE requests, CONNECTED
constraint, exponential 30s backoff and periodic recovery. Non-Android hosts already
own cancellable ForegroundSyncWorker with bounded backoff. Do not introduce another
SDK send queue or a screen-owned forever loop.

1. Add core.sync.domain dependency to collaboration.domain; QueueCollaborationText
   receives SyncScheduleRepository. Once exact stored intent is confirmed, request a
   constant feature-owned key. Preserve a successful local storage acknowledgement
   when scheduling fails; periodic/startup recovery finds durable pending commands.
   Never create an alternative ID. Request again when replay finds a non-Sent intent.
   Bound scheduling to a short interval; keep cancellation/owner checks around it.
   Tests: local commit survives schedule failure, exact replay reawakens once, cancelled
   write cannot fabricate success, Sent completion does not request transport again.
   Only three construction callsites currently exist: data DI, domain MessageIntentFixture,
   presentation ChatThreadFixture. Do not silently add an optional no-op production port.

2. Add a local selected-workspace outbox discovery use case. It reads Session.current,
   selected Workspace.current, validates local collaboration access (ordinary expiry
   preserves owned records), checks shared admission, reads messages.observe(access,null)
   once, rechecks session/workspace/admission, validates bounds and exact record ownership,
   returns workspaceId + account/company + finite conversation IDs (no body in scheduler).
   Null/no workspace is empty work; unknown/corrupt/denied state is a classified failure.
   Native/live permission is rechecked by each existing transport use case.

3. App-level CollaborationDeliveryWorker orchestrates real feature use cases like
   InboxPushWorker, under one mutex and bounded total timeout. Load setup / ensure existing
   runtime; no silent enrollment, bootstrap or failed room retry. For each finite room:
   reconcile journal, resume retained room command or prepare NOT_REQUESTED room only
   when a pending intent exists, reconcile confirmed control, wait via scheduler if not
   ready. Stage a bounded oldest batch, deliver the exact mapped room queue, acquire
   originals, recover missing receipts, retire proven completion. No already-confirmed-only
   native resume. Continue independent room work where allowed; company/session denials
   stop scope immediately. Fairness/retry state needs tests, not an unbounded first-room loop.
   Use scoped child timeline only if native sync requires it; inspect actual adapter before
   choosing. Every acquired child is closed in finally even for late cancellation.

4. Register SyncTask in ApplicationWorkerModule. Application collaboration ownership
   changes can request the key after a valid owner returns, so blocked company/session
   recovery wakes without waiting for periodic work. Avoid coupling a route to a worker.
   Startup and process-death tests must prove the durable command is the work source.

5. Thread then keeps local queueing + timeline/history observation; transport work belongs
   to the application worker. Refresh may explicitly request delivery. Retain distinct
   signal for manual retry of failed room provisioning, not a silent background retry.
   Validate race between active thread and background worker; shared use-case mutexes and
   exclusive native queue lease retain exact identities. Change UI claims only after proof.

Remaining outside this increment: provider older-page UI, durable drafts/cancel,
notifications/inline replies (pusher/gateway/NSE), iOS real background limits/web service
worker, groups, cross-family cryptographic key continuity, all rich content/calls/meetings.

`RefreshWorkspace` is already a registered periodic SyncTask; do not bypass its live
account/assurance/policy checks. A successful ownership renewal can wake blocked chat
work. Its failure still preserves owned local history and pending intent.
Android runtime `enterTimeline` starts sync and may join; `acquireMessageQueue` alone
does not join a missing room. A background delivery pass needs a scoped admitted child
when opening a ready room for the first time, with guaranteed cleanup.
