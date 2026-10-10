# Local-first data and sync

The dashboard is the reference implementation. Its content and saved activities live in
the local database. The Activity tab's star updates local state immediately; synchronization
is separate from saving. The current HTTP transport is an in-process demo, not a deployed
backend or a cross-device service.

## Ownership

| Component | Responsibility |
|---|---|
| `DashboardStore` | Raw database operations and transactional writes |
| `DashboardRemoteDataSource` / `DashboardApi` | Raw HTTP calls and DTOs |
| `LocalFirstDashboardRepository` | Source selection, mapping, storage/network failure boundaries |
| `SetActivitySaved` | Commit locally, then request execution without undoing a successful save |
| `SyncDashboard` | Ordered push, acknowledgement, content refresh, retry classification |
| `DashboardViewModel` | Observe local snapshots, handle actions and display explicit refresh outcomes |
| `SyncTask` | Domain entry point implemented by each feature's synchronization use case |
| `core/worker` | Platform execution, constraints, backoff timing, cancellation and task dispatch |

Workers are application entry points. They execute use cases; they do not manipulate feature
tables or invoke HTTP APIs. Scheduler datasources enqueue work and never execute a use case.
Repositories do not call other repositories; use cases do not call other use cases.

## Data flow

1. `SetActivitySaved` calls the repository. The store changes the local preference and inserts
   an outbox command **in the same transaction**. Failure rolls back both changes.
2. Database observation updates the UI. A scheduling failure is returned separately from the
   successful save; the durable command remains available for a later run.
3. A worker or explicit refresh executes `SyncDashboard`. Its container-scoped mutex serializes
   overlapping foreground, periodic and explicit executions. Each pass processes at most 50 commands.
4. Commands retain their UUID across retries. The API receives it as `Idempotency-Key`. Only a
   successful push permits deletion of that exact operation ID. Later edits remain queued.
5. The use case refreshes remote dashboard content into the database. The UI continues reading
   the database, including when refresh fails. Server content never replaces device-owned saved
   preferences. Pending commands and preferences survive process restarts.

Saved preferences are a **push-only example**. Dashboard content is a server-owned snapshot.
There is no generic last-write-wins resolver, bidirectional preference merge, change cursor or
remote tombstone protocol hidden in the repository. Those policies depend on the real backend.
The demo acknowledges valid commands and checks idempotency within its process; its server-side
state is not persistent. Local storage is persistent.

## Workers

| Platform | Execution |
|---|---|
| Android | WorkManager one-time chains and a unique periodic request per task; connected-network constraint; exponential backoff starting at 30 seconds |
| iOS | Foreground worker while the scene is active; registered `BGAppRefreshTask` while backgrounded, with expiration cancelling the Kotlin job |
| Desktop | Foreground worker owned by the application process |
| Web | Foreground worker owned by the page; an `online` event wakes pending tasks |

The periodic interval is 15 minutes. Android/iOS may defer work for power, connectivity,
background restrictions, or other OS decisions. Neither platform provides an exact schedule.
Android persists its work requests; iOS launch opportunities are controlled by the OS. Desktop
and Web do not run after their process/page closes. Reopening starts another pass over the outbox.
The Web notification service worker is not a general Kotlin background-sync runtime or an offline
asset cache. Keep one active application owner per environment; cross-tab synchronization is not
implemented.

Android uses `APPEND_OR_REPLACE` for explicit requests: a request racing with the final moments
of an existing worker must not be dropped by `KEEP`. Periodic work provides recovery if the
process ends after a database commit but before enqueueing. No feature payload lives in WorkManager
`Data`; the outbox is the durable source. iOS uses the same feature use cases and cancels their
execution when its permitted background window expires.

Successful bounded batches continue in the foreground after a short yield; they do not
increase the network retry backoff. Android schedules continuation with WorkManager retry
timing. Network, timeout, HTTP 429 and server failures request backoff. Validation, authorization, storage
and unexpected failures stop that execution and preserve pending data. A later explicit request,
startup, or Android/iOS scheduled opportunity can try again. Cancellation propagates without
acknowledging a late upload result. Delivery is **at least once**, not exactly once.

## Add a task or connect a backend

- Implement `SyncTask` in the feature domain. Keep its key stable across upgrades.
- Register one singleton use case with `@Single(binds = [SyncTask::class, YourSync::class])`
  in the feature's existing data DI module. Hosts discover registered tasks with `getAll<SyncTask>()`.
- Put local edits and their outbox records in one transaction. Keep operation IDs stable until
  acknowledged. Do not delete pending changes after a retry count or a connectivity check.
- Replace the demo engine with an authenticated transport. The server must commit the mutation
  and idempotency receipt atomically, reject key/payload mismatches, and retain receipts for the
  supported replay window. For the example API: `PUT /dashboard/preferences`, JSON
  `{ "activityId": "1", "saved": true }`, `Idempotency-Key: <UUID>`, success `204`.
- Define account/device scope, logout cleanup, authorization recovery and conflict rules before
  synchronizing real user data. Current demo data is installation/environment scoped. Shared
  editable data requires a server revision and an explicit conflict-resolution contract.
- Use incremental pages/cursors and deletion markers when replacing the sample snapshot endpoint
  with a large dataset. Commit and test the corresponding database migrations.

## Validation

```sh
xvfb-run -a ./gradlew :check
./gradlew :core:database:wasmJsBrowserTest :core:worker:wasmJsBrowserTest
./gradlew :apps:android:assembleDevDebug
```

Tests cover local write/outbox rollback, v1 database migration, restart persistence, acknowledgement
replay, edits during upload, cancellation with a late response, concurrent executions, bounded
batches, retry timing, task isolation, and host disposal. SQLite and IndexedDB run the same storage
contract against their actual databases.

## Paged activity

Activity demonstrates explicit refresh, load more and retry. The first page and
cursor persist in Room/IndexedDB. An append commits only when the originating
session ID, refresh snapshot and expected cursor still match; duplicate activity
IDs appear once and saved preferences remain device-owned. The ViewModel drops
concurrent load-more requests and cancels them when refreshing or being cleared.
Database guards also reject stale results from background refresh/account changes.
A failed load-more retains the current list and cursor for retry. Refresh starts
a new first-page snapshot; cached later pages are replaced, including on sync.

## File queue

`SyncTransfers` adds the `file-transfers` task to the existing worker registry. It processes
a bounded batch and leaves durable checkpoints on cancellation. See [file transfers](file-transfers.md)
for platform limits, offset reconciliation and pause/account-change ownership.
