# Worker runtime

This infrastructure module executes domain `SyncTask` implementations. It does not own
feature repositories, HTTP calls, permission policy, or database transactions.

- Android: `SyncWorkerFactory` supplies registered tasks to WorkManager. The application
  implements `Configuration.Provider` and removes only the default WorkManager initializer.
  `AndroidWorkScheduler` persists one-time requests and registers unique periodic requests.
- Other hosts: `ForegroundWorkScheduler` provides a separate conflated wake-up channel for
  each task. `ForegroundSyncWorker.start(scope)` returns the job owned by the host. Cancel
  that job before closing the scheduler and application resources.
- `RequestedSyncWorker` executes only explicit requests from a conflated foreground
  lane. It has no startup scan, periodic timer or result-driven retry. Use it alongside
  a durable OS scheduler when a newly persisted command must bypass an older OS
  backoff. WorkManager remains responsible for failed or unfinished passes. A request
  received during execution remains pending for the next pass; stopped hosts retain
  signals in the application-owned scheduler until a replacement runner starts.
- `SyncResult.Retry` requests exponential backoff; `Blocked` stops retries for that execution.
  Foreground blocked tasks wait for an explicit wake-up. Periodic OS tasks may execute again.
- Expected failures stay in domain results. `runSyncTask` retains unexpected diagnostics and
  propagates cancellation. WorkManager and host cancellation must never be converted to success.

Foreground work cannot survive process/page termination. Durable payloads belong in an outbox;
queue signals only request a pass over it. A host must start its runner once, validate task keys,
and arrange appropriate OS background opportunities separately.

Run `./gradlew :core:worker:desktopTest :core:worker:wasmJsBrowserTest` for scheduling,
coalescing, task isolation, cancellation and retry tests.
