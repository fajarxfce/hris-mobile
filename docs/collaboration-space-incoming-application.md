# Application incoming worker

The registered incoming task consumes durable room demand without a screen owner. One
application instance serializes turns; each turn captures at most four eligible rooms and
acquires at most one provider page and one pending-content page per room. Content commits
before older provider metadata finishes. Successful unfinished rooms move to the FIFO tail;
missing keys and transient failures retain bounded retry eligibility. A new parent cancels
the previous turn. Current admission and exact generation checks remain in domain use cases.

The application adapter owns execution lifetime, time limits and scheduling. Repositories own
encrypted I/O; use cases own admission, source completion and retry policy. Android adds the
independent incoming key to collapsed WorkManager scheduling. Android/iOS share the task;
unsupported hosts do not start native work. No second Matrix client or sync loop is created.

History traversal positions include logical device identity. Retained account demand can
therefore outlive the position it originally referenced. RecoverCollaborationSpaceIncomingProvider
restarts Latest once if the current device has no progress. Failed storage reads cannot become
absence, and progress arriving before commit prevents the reset. The previous device's history
is preserved. Separately, an exhausted page that has not exposed a known requested head retains
a delayed restart. Exact readback makes lost responses and repeated recovery idempotent.

Focused validation passes 615 cases; full application validation passes 10,901. The additional
17 domain cases run on JVM and actual Chromium; 20 application task cases cover registration,
finite turns, fairness, missing progress, missing keys, parent changes, concurrency and timeout.
The full gate checks Android host tests, release compilation/lint, Wasm and iOS source, the
proof APK, formatting, architecture and detekt. This does not establish native worker or
end-to-end realtime acceptance. SDK hints, root publication, push and verified new-key handoffs
remain required. No live rollout or phone latency improvement is claimed.

Initial focused1 passed603 tests but detekt rejected a long test factory. The fixture now owns
one worker, matching application singleton lifetime; no suppression or helper-only extraction.
Focused2 includes the current-device progress recovery regression and passes all615 cases.

[Focused evidence](measurements/collaboration-space-incoming-application-focused-2026-11-10.json)
and [full evidence](measurements/collaboration-space-incoming-application-full-2026-11-10.json).
