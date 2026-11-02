# Bounded all-company recovery worker

`CollaborationRecoveryWorker` is registered once under the fresh
`hris.collaboration-recovery` task key. Android opts only that key into the existing
two-slot scheduler; ordinary task keys retain their prior behavior. WorkManager
input contains only the task key. Periodic execution includes the new registered
task. Desktop/web return without capturing native ownership or creating wakes.

Application startup observes the existing processed-parent publication. A changed
non-null native parent persists a fresh recovery wake before requesting execution.
Same-parent token renewal does not replace that wake; logout clears the remembered
parent. Selected-company delivery remains a separate task. Cleanup failure or a
selected-company denial does not invent an absent native parent. Neither startup
scheduling nor publication admits SDK work or changes the foreground workspace.

The consumer admits at most two calls: one processor and one waiter. The waiter
holds no account grant or native resource. Admission beyond two returns Retry.
Waiting count is separate from admitted count so a finishing call cannot be mistaken
for future processing. Wait plus execution is bounded to 180 seconds; processing
is bounded to 90 seconds, initial parent observation to five seconds and each
company attempt to 30 seconds. Current native admission keeps its own original
monotonic deadline, including its transport time. Parent replacement, logout or a
stopped source cancels and joins the owned processing job and monitor. Foreground
company changes alone do not replace the native parent.

`EnsureCollaborationRecoveryWake` reads or creates a pending hint for a running
periodic consumer without recursively enqueueing it. The worker captures account
progress and requests at most two live device references after its saved seek.
Each Attempt is durable before admission and company work. Inactive/revoked/foreign-
platform references are covered without enrollment or content acquisition. Complete
preserves retry and healthy-continuation requirements; another company can progress after a transient failure.
A blocked attempt leaves its underlying commands and required user action intact.
Sweep completion is coverage of these attempts, not message-delivery acknowledgment.

Only a validated directory end can Finish the sweep. Interrupted or failed sweeps
restart without acknowledging their wake. A clean local Finish precedes the exact
original global ACK; the worker checks pending state again even after a successful
ACK. A newer wake requires its own sweep. An already queued execution may perform
another bounded sweep; this never reuses message IDs or deletes pending commands.

Healthy pagination uses an actual waiting invocation or requests a same-generation
continuation, then completes. It does not deliberately increase WorkManager backoff
for each successful page. When platform completion bookkeeping races enqueue, there
are at most three scheduling attempts with 100ms gaps; each request has the existing
1.5-second budget. Failure retains the wake and returns Retry. A cancelled successor
also leaves durable work for later startup/periodic recovery. Urgent FCM latency and
Doze behavior require separate integration and measurement.

The application currently supplies the existing captured outgoing delivery worker
as the company processor. Its shared no-wait delivery mutex, draft/command recovery,
fresh setup, native admission, lease ownership and original-message reconciliation
remain unchanged. That processor still exits when no outgoing work exists. Incoming
room discovery/history acquisition, fair room progress, FCM receive, notification
privacy/actions and encrypted inline replies remain necessary before push activation.
No live backend activation or employee release is implied by task registration.

[Validation](measurements/chat-company-worker-2026-11-02.json) binds exact source and
reports. Sixteen new application cases use actual domain operations with explicit
in-memory repository outcomes, exercising concurrency, cancellation, late account
changes, company denial, timeouts, progression and scheduling/ACK gaps. Existing
SQLite/IndexedDB tests prove the encrypted progress repository separately. Three new
domain cases cover periodic wake creation, failures and cancellation; startup tests
cover stable-parent renewal and retained scheduling failure. The assembled container
resolves the singleton task and its dependencies. Android release/proof Kotlin
compilation is included. Actual Android consumer/OS/storage execution now passes the [native proof](measurements/android-company-recovery-2026-11-02.json): 40 failed enqueues retain the newest wake, two OS tasks serialize company processing, old ACK cannot erase a new wake, and process restart preserves an uncertain attempt while advancing later companies. All three scenarios complete in 35.839 seconds; proof process and owned emulator are stopped. Directory, admission and company callbacks are synthetic; SDK/provider/FCM acceptance remains separate.

Per-company `SyncResult.Retry(null)` denotes successful bounded work with more
pending commands. It now sets the independent durable continuation flag. At sweep
end, Finish first commits the restarted sweep; a healthy continuation schedules the
same wake without OS failure backoff. Real failures or interrupted attempts win even
when other companies report healthy remaining work. Scheduling failure still returns
Retry and retains the restarted sweep and original wake. No pending command is
acknowledged by this coverage marker. Incoming recovery remains the next integration.

The [continuation regression evidence](measurements/chat-company-continuation-2026-11-02.json)
records two domain, three shared storage and four app cases plus real Android
three-batch execution: all seven companies advance in order across21 attempts,
12 OS task completions, zero Retry results,1.593s. Burst and interrupted-process
regressions also pass; all four scenarios total37.916s. The measurement preserves
both failed fixtures, the corrected source hashes and emulator cleanup. Full root,
affected browser and app Wasm checks pass on the final ten inputs.

Hosted [Apple41405822268](https://github.com/fajarxfce/hris-mobile/actions/runs/41405822268)
at consumer338fea17 is completed/audited:112 core simulator cases in30 suites,
zero failures/errors/skips, six raw wake cases0.122s and full Xcode app1303s.
Fourteen exact source hashes match that commit. Core simulator suites do not run
the app worker's desktop cases; this earlier run excludes the continuation change.
