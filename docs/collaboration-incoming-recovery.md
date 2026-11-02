# Incoming recovery without an outbox

`CollaborationIncomingRecovery` is an application coordinator, registered once and
called by the company recovery task. It does not register another OS task. Incoming
and outgoing work receive separate finite turns, using the same original company
admission; see [company scheduling](collaboration-company-worker.md).

The incoming turn is bounded by15 seconds, remaining original wall-clock admission
and remaining monotonic admission. A no-wait mutex rejects a concurrent incoming
turn without retaining another grant or SDK resource. Fresh background setup must
match the admitted owner/device and an active device of at least the observed
version. No workspace selection is read or changed. Confirmed local denial remains
closed until the existing live setup/list acquisition readmits it.

The worker requests one live summary after the saved room seek. It persists Attempt
before room work, then Complete with independent retry and healthy-continuation
flags. Each room gets at most12 seconds within the original incoming budget. A
validated directory end atomically resets the room position and returns Covered,
Continue or Retry. The generation-bearing empty row is retained; it is not deleted.
Failure or an unfinished attempt stays sticky across later rooms and process death.
A healthy next page returns Retry(null), preserving immediate company continuation;
real failure requires retry/backoff when that sweep reaches its end.

NOT_REQUESTED rooms are covered without creating a room. QUEUED/RUNNING provisioning
retains retry. FAILED/CANCELLED provisioning remains a user-action condition, without
automatic reprovisioning. READY rooms borrow an admitted native runtime, monitor local
access and runtime interruption, enter an owned timeline, and acquire the latest
original window through the existing SDK/history use case. Cancellation, account
replacement and known company denial gate late results. Timeline and runtime are
closed in joined cleanup, including an allocation returned after cancellation.
Incoming work never enrolls devices, restores through the foreground entry point,
or enqueues an outgoing message.

Acquired originals use the existing encrypted account/company history repository.
This initial caller covers one bounded latest window (at most128 events), not all
messages from an arbitrarily long offline gap. It never resets the foreground
Older provider position. Independent recent catchup traversal, exact reference
reconciliation, push prioritization, notification publication and replies remain
subsequent work. A completed room/company sweep is not a delivery receipt.

The application tests use actual domain operations, assembled DI and SQLite, with
fictional HTTP/provider outcomes. They cover no-outbox restart, fair room progression,
provisioning states, fresh readmission, original deadlines, contention, cancellation,
late allocation/account/denial, and the actual combined incoming/outgoing binding.
The temporal coordinator tests use virtual time to prove separate budgets and joined
cleanup. Android WorkManager scheduling and real SDK/provider acceptance have separate
proofs; do not infer FCM acceptance from these tests.

[Recorded validation](measurements/chat-incoming-worker-2026-11-02.json) passes14
incoming and7 coordinator cases,91 focused app cases total, full3,123 desktop/215
Android host/865 affected browser/10 build-logic, Android release Kotlin/proof APK
and static checks. All four actual WorkManager scheduling regressions pass46.417s
under the one-company quantum, including21 healthy callbacks/21 task completions
without Retry. Native directory/admission/company callbacks are synthetic; SDK and
FCM acceptance remain separate. Failed fixture assertions and exact input/report/APK
hashes are retained. No employee installation or server activation by this increment.
