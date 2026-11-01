# Application-owned chat delivery

`QueueCollaborationText` acknowledges exact encrypted local intent before requesting
`hris.collaboration-delivery`. Scheduling failure or a 1.5-second scheduling timeout
cannot undo that local acknowledgement. Retrying the same non-Sent command requests
work again without changing UUID/body. Cancellation and account/company checks still
prevent a late acknowledgement. `RequestCollaborationDelivery` is an explicit local
wake-up with the same ownership/gate checks; it reports scheduling failures and never
acquires credentials or an SDK runtime.

## Ownership and platform entry points

`CollaborationDeliveryWorker` is a singleton `SyncTask` in the application container.
Android's existing `SyncWorkerFactory` resolves this same instance for one-time and
periodic work. One mutex serializes its invocations. WorkManager persists only the task
key with connected-network constraints, 30-second exponential retry and 15-minute
periodic recovery. Startup/renewed application ownership also requests the key. Other
hosts use the existing cancellable foreground scheduler. These APIs do not override OS
force-stop, background restrictions or iOS background execution limits.

The durable command store is the work source. Local discovery reads the selected
company's at-most-128 retained commands, validates ownership/state/IDs and returns a
finite set of conversation IDs. Ordinary credential age preserves this local work;
known shared company denials remain gated. Every native/control operation independently
re-admits live access. Missing device setup or protected runtime requires explicit setup;
the worker never enrolls, bootstraps or silently retries failed provisioning.

## Bounded progress and recovery

Each pass has a 60-second timeout and a 12-second room timeout. Before touching a room,
an encrypted persistent cursor advances by compare-and-set. Its generation changes on
every advance, including returning to a previous conversation, so stale A→B→A attempts
cannot match. Restarted work rotates sorted IDs after the saved turn. Cursor ciphertext
uses a separate authenticated purpose/partition under the existing protected history
key; it contains no message body. Lost commits, key loss and scope/tamper checks have
SQLite and IndexedDB contract coverage.

The worker reconciles saved receipts, reads room readiness and replays retained room
operations using their exact IDs. Only a durable pending intent can create a previously
unrequested room. Confirmed provisioning receipts are reconciled without replay; room
readiness is independent. Failed/cancelled provisioning awaits the user's explicit
retry. Other rooms continue when scope admission permits it. A blocked room does not
stop the next bounded batch of healthy work or newly queued conversations.

A scoped child timeline starts the required native sync/join. It is released in a
non-cancellable finally block, including late acquisition and cancellation. Retained
non-Queued commands first acquire original history and recover missing receipts before
staging/resuming. At most eight pending records are selected for staging in one room
turn; delivery uses only the admitted mapped native transactions. Confirmed-only work
does not reopen native delivery. An empty native queue is never interpreted as an ACK.

After sending, original sender/content evidence supports receipt reconciliation and
completion retirement. Missing originals cause backoff while keeping durable intent.
A known Sent event anchor permits one explicit focus-capacity reopening and the same
anchor retry. Without a receipt anchor, the worker resolves and validates the current
owned SDK head, re-admits the ready room, then reopens once before acquiring originals.
It does not send a message to recover history. Initial opening/closing runtime contention
also uses bounded retry backoff. [Latest-head recovery](collaboration-history-recovery.md)
includes the corresponding explicit foreground Refresh behavior. Complete provider
history traversal remains separate; no evidence beyond the bounded original window
is inferred.

## Screen boundary and validation limits

The thread keeps local queueing, canonical-history observation, live child presentation
and debounced original-history acquisition. It requests application delivery instead of
staging, sending, acknowledging or retiring messages itself. Closing the screen does
not remove queued intent. Explicit retry of failed room provisioning persists the retry
operation and wakes the worker; the worker performs its transport. Readiness polling
remains finite and read-only in the screen.

Desktop integration uses the real application container, encrypted repositories, SQLite
and domain use cases, with a simulated control-plane wire and native provider. It covers
container restart, lost native ACK, lost room response, missing originals, known-anchor
capacity recovery, cancellation/late children, owner loss, explicit setup, startup wake-up
and blocked-room batch fairness. This is not Android WorkManager process-death or actual
SDK/server proof. Existing earlier provider proofs are not relabeled as this increment's
acceptance. Current build/test evidence is recorded separately in the delivery measurement.

Remaining product work includes native worker interoperability, remote paging, durable
drafts/cancellation, incoming push and inline replies, groups, rich content/actions,
cross-family key continuity, Apple/web runtime adapters, calls and meetings. No complete
PRD feature group is accepted by this delivery increment alone.

[Measured validation](measurements/chat-background-delivery-2026-11-01.json) records all
32 matching source inputs and eight attempts. Full checks pass in 320 seconds: 2,700
desktop tests, 352 domain / 233 data / 56 presentation browser tests, 170 Android host,
12 worker integration cases and two binding cases, with no failures/errors/skips. Android
lint has no errors and one existing warning. APK/AAR artifacts are built, not installed.
