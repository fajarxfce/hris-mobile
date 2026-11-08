# Foreground execution of persisted recovery

Android's existing scheduler notifies the application after feature intent has been
persisted, before inspecting WorkManager's queue. Recovery now has a separate
`RequestedSyncWorker` lane in the process's STARTED lifetime. A newly requested
recovery can execute promptly while an older OS request waits in backoff.

The core runner owns only execution: one consumer per registered, conflated task
lane, cancelled with its host scope. It neither reads feature repositories nor
interprets business failures. It has no startup scan, periodic timer or result-driven
retry. WorkManager retains durable retry responsibility. Delivery keeps its existing
separate runner and policy. Both entry points share the same recovery-worker instance
and its bounded processor exclusion.

Signals arriving while a task executes remain available for the next pass. Signals
waiting during a stopped host survive in the application-owned scheduler; process
death is covered by persisted feature intent and OS work, not the in-memory channel.
Domain recovery still owns current admission, finite company traversal and exact wake
acknowledgment. A new wake does not reset an unfinished sweep: the old sweep completes
before the newer generation is covered, preserving its event instead of losing it.

[Focused validation](measurements/collaboration-requested-recovery-2026-11-08.json)
covers40 portable worker cases across JVM and actual Chrome, plus34 application
recovery cases. This includes cancellation, conflation, lane isolation, all task
outcomes without polling, an old failed sweep followed by a new wake, and competing
OS/foreground execution. The initial application assertion incorrectly expected a
new wake to replace the old sweep; corrected coverage verifies both generations and
the absence of further timer-driven execution. Production code did not change.

The passing focused run also compiles Android and iOS worker source and passes
format, architecture and detekt checks. Its source manifest includes the copied,
ignored Firebase Android config from the main mobile checkout. Full repository
checks, native activation and the generic Starter port are still pending. This
runner does not implement the server's post-commit recipient-readiness wake.
