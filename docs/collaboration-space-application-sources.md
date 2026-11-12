# Process-owned incoming sources

ApplicationCollaborationLifetime owns one child that observes admitted local startup and actual
installed runtime publication. The child starts the existing key-retention and sync-retention use
cases; it never acquires another client, sync loop or native close handle. Exact instance changes,
root/account loss and retirement cancel and join old collectors before replacement. A second
process collector waits before observation. Natural completion clears the previous publication.

A finite key failure leaves its receipt pending. A real host foreground entry starts another finite
observation pass, including after an unexpected observation failure; no registration retry timer is
introduced. Sync and key children fail independently. Failed sync results do not discard later
independent batches. Diagnostics contain only failure categories, with duplicate sync failures
suppressed. UI routes do not own these lifetimes or display internal state.

SDK8 already emits reconciliation at initial key-listener registration, including restored stored
keys, and key imports notify that stream. The existing exact durable receipt covers startup; no new
schema or separate startup request is needed. Native listener disposal belongs to the runtime.

Canonical Original metadata/index or Published metadata precedes conversion to primary incoming
work. New physical hints remain primary work even after a history sweep has completed. Unresolved
primary work is transferred only after exact metadata/history confirmation into a fresh account
recovery request. Opening an empty conversation creates no acquired history; prepared binding
retains its existing recovery wake. Existing worker fairness and finite page sizes remain intact.

The application wiring is enabled in source. This is local/protocol validation; native SDK request
counts, late keys, storage uncertainty, offline/restart and Apple native implementation remain
separate acceptance work. The live Group gate and services are unchanged.

[Exact validation evidence](measurements/collaboration-space-application-sources-2026-11-12.json).
