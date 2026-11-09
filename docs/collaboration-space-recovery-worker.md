# Original organization recovery worker

The application registers an independent task for `hris.collaboration-space-recovery`.
A scheduled discovery creates or reuses a durable wake. Requested continuations only
consume an existing wake; a late duplicate cannot create another completed sweep.
One processor and one waiter retain current application native-parent ownership.
Processing is bounded to ninety seconds and four raw inventory pages; queue admission
has a separate three-minute ceiling. Parent replacement cancels owned work.

The domain selects retained root, registration, bootstrap and protected original
handover coordinates without enrolling or creating keys. The existing owning adoption
and synchronization use cases operate on the same published original SDK client.
Each raw page is claimed before room work. A pending claim is recovered first after
interruption; empty index pages advance without acquiring a room. Original-only
delivery remains the sole owner of provider admission, exact SDK transactions,
receipts, settlement and final writer drain.

Unresolved reports retain a fault and use scheduled retry, never immediate healthy
continuation. A final current-inventory read detects new same-generation bindings and
restarts the captured wake without acknowledging it. Deleted, replaced or rewound
namespaces remain unavailable. Exact checkpoint CAS protects late completion;
acknowledging an old wake cannot clear a newer generation. Producer writes after the
final read need their own durable wake; scheduled/startup discovery remains necessary.

The application subscribes to group readiness before startup recovery. Its native
parent publication remains independent of selected company. The explicitly owned
subscription stops with application disposal; collector or scheduling failure cannot
stop the existing native lifetime. Task registration has a separate application DI
module, while all domain use-case construction remains in the feature data module.
Resume scheduling is lazy because the scheduler discovers its keys from the task list.

Validation passes 97 focused cases in 85.225 seconds. The final full gate completes
in 253.361 seconds with a validated set of 4,439 cases: 3,773 desktop, 629 Android
host and 37 browser cases, with zero failures, errors or skips. It reruns 2,489 cases
and reuses 1,950 passing cases from the first full gate. All 240 reused XML hashes
match and their production inputs are unchanged. The earlier failed startup suite
reruns successfully; reused results are not counted as fresh executions.

Root check, Android release/lint, shared Wasm/iOS source and quality checks pass.
All 4,627 source inputs match between focused and final gates. Current source,
Inspector and archived XML hashes are verified in the
[measurement](measurements/collaboration-space-recovery-worker-2026-11-09.json).
Initial fixture, LongMethod and startup expectation failures remain recorded there.
The corrected integration test retains the company and delivery assertions, then
verifies the new task's durable wake. CI includes the group worker and application
lifecycle cases. No checks were suppressed.

Current worker attempts original rooms conservatively. A future optimization must
combine the existing complete paused SDK inventory with app commands/receipts before
claiming there is no work. This worker does not enqueue new SPACE messages or retire
history. Group push routing,producer wake integration,legacy/product routing and
actual provider/process-loss proof remain before rollout. No new APK or phone update
is included by source compilation alone.
