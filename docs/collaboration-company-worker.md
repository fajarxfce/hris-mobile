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
company attempt to 50 seconds, including fresh admission and bounded recovery work. Current native admission keeps its own original
monotonic deadline, including its transport time. Parent replacement, logout or a
stopped source cancels and joins the owned processing job and monitor. Foreground
company changes alone do not replace the native parent.

`EnsureCollaborationRecoveryWake` reads or creates a pending hint for a running
periodic consumer without recursively enqueueing it. The worker captures account
progress and requests at most one live device reference after its saved seek.
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

The application supplies `CollaborationCompanyRecovery`. [Device sync](collaboration-device-sync.md)
and incoming acquisition run as joined children, each with a 15-second turn.
Outgoing delivery and push registration follow, each with its existing 15-second
turn and the exact same original admission. The critical path remains 45 seconds
inside the 50-second company budget. Failure in one turn does not skip the others;
external cancellation joins both initial children and does not start later work.
None renews admission. Real Retry failures win over healthy continuation, while
blocked work retains the required user action. One company per quantum stays
within the 90-second processor budget; structured cleanup remains owned.

[Incoming recovery](collaboration-incoming-recovery.md) now discovers/acquires
originals independently of the outbox. The existing captured delivery worker's
shared no-wait mutex, draft/command recovery, original reconciliation and separately
registered foreground task remain. FCM receive, notification privacy/actions,
encrypted inline replies and independent long-offline catchup remain necessary
before push activation. No live backend activation or employee release is implied.

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
acknowledged by this coverage marker. Incoming recovery shares the same healthy-continuation contract.

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
