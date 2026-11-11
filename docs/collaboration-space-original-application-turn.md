# Original incoming application turn

Typed Company-backed incoming tasks now run through the existing application's incoming worker.
The worker dispatches an Original target before the Published loader, preserving its single
processing mutex, combined FIFO, parent cancellation, forty-second room deadline and independent
wake. `CollaborationSpaceOriginalIncomingTurn` owns one invocation, without an independent client,
job, timer, collector or scheduler. Its application DI module binds the existing domain owners.

An invocation acquires at most one provider page. After advancement it reloads the persisted task
and resolves its current Company/physical-device coordinates. It never synthesizes a Pending phase.
An unfinished provider page permits a later bounded turn without imposing a keys backoff.

For Pending work, the turn reads a finite Company page, reconciles already retained local facts,
and reads the remaining page again before acquiring content. Already resolved messages therefore
require no content request. Reference completion and incoming task retirement remain separate
owners. More finite work permits another turn; missing keys keep the Pending phase and use existing
worker deferral. A missing provider checkpoint goes to its existing recovery owner. A lost reply
does not infer retirement: the worker defers the actual retained task and phase.

Application callbacks invoke use cases; policy, repository I/O and authorization remain in those
use cases. The adapter has no repository/client access. True means this captured turn needs no
deferral, not that the queue or wake is empty. The existing finish owner independently confirms
drained demand. Parent cancellation joins in-flight work before replacing the runtime.

Validation includes 219 focused desktop cases, 153 browser cases and
12,222 full local cases. Thirty new application cases cover twenty-two turn scenarios and
eight existing-worker scenarios, including concurrent triggers, cancellation, forty-second timeout,
access denial and lost provider replies. Existing Published/physical worker, immutable Original
metadata, queue, Company incoming and finite-wave storage controls remain included. The application
fixture models callbacks; it does not prove native SDK/storage interleaving. Shared Wasm and iOS
metadata compile, Android release compilation/lint and proof APK checks are required.

The raw sync collector and Original producer remain disabled. Logical LEGACY alias handoff,
bounded SDK room inventory, keys-only wakeup and combined original-worker storage/native adoption
proof still precede activation. No provider cursor is relabeled, and a source identity cannot create
a sync position. Hosted Apple execution, physical-device installation and live deployment are
separate evidence. All 54 communications PRD groups remain open. No generic Starter change arises
from this HRIS application orchestration.

[Exact validation evidence](measurements/collaboration-space-original-application-turn-2026-11-11.json).
