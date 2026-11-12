# Serialized history recovery service

Incoming selection now exposes eligible and delayed account-history recovery independently of
primary queue capacity. The existing worker adopts an available runtime for history-only work,
services one history visit after reserved inventory and before primary FIFO work, and reads current
demand before continuation. Delayed history schedules retry; completed unresolved passes remain
asleep until new key, source, restore or primary-transfer demand is retained.

`CollaborationSpaceHistoryRecoveryService` is an application orchestrator within the worker's
existing mutex and parent lifetime. It selects at most one account metadata row, preserves its
actual Company or Space and historical device, reads at most16 references, invokes existing
local-before-native content acquisition and local completion, then completes the global turn.
Copied input lists survive further suspension. The complete visit has a30second bound; failure
settlement has at most5additional seconds. Authority and validation failures remain failures.
Conflict and explicit queue contention get one selection readback, without a backoff write. A
later requested generation alone does not establish that the currently active turn advanced.

Both Original and Published primary owners invoke the admitted key-wait transfer after finite
pending-reference EOF. The exact persisted Pending capture is used after provider advancement.
Successful transfer releases the primary slot while retaining references and new recovery demand;
an uncertain transfer failure remains a failure. A false transfer allows the existing guarded
provider-recovery owner to inspect missing-head coverage. No provider fetch runs inline in that
fallback. More pending references continue their finite pass before transfer.

The existing90second parent and serialized worker retain ownership. Tests cover history-only and
delayed work, completed-pass sleep, new demand, a64room queue, reserved inventory/history service
before slow primary processing, cancellation, source replacement, concurrent triggers, finite
pagination, storage failures and obsolete captures. Application fixtures establish sequencing;
existing domain and storage suites separately establish admission and atomic-write contracts.
Koin resolves the new service explicitly, and hosted CI selects both new application suites.

This wires the history service and transfer operations. Native key listeners, startup/source/restore
producer activation and native execution remain separate work. All54PRD groups remain open.
No live deployment or phone installation is implied by these tests. The change is HRIS policy
and orchestration, so no Starter port is required.

[Exact validation evidence](measurements/collaboration-history-recovery-worker-service-2026-11-12.json).
