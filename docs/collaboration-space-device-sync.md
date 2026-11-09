# Admitted synchronization of the original organization device

SynchronizeCollaborationSpaceDevice performs one bounded initial synchronization on
an already published logical organization runtime. It captures the current native
parent, checks retained directory admission and exact bootstrap facts, reads current
device metadata once, and verifies the same runtime before and after native I/O.
It never prepares registration/bootstrap work, creates a store or joins a room.

The pure policy matches the organization/native owner, device, bootstrap operation,
platform, device version and original gateway family. Ordinary gateway token rotation
can advance its version within that family. An expired initial access token does not
replace owned facts; the current gateway hard interval is charged against local
elapsed time, independent of the phone/server wall-clock difference. Exact device or
gateway revocation remains an access denial. A foreign identity cannot supply a
revocation for the current one. The native parent and the entire operation retain
separate deadlines.

The new narrow CollaborationSpaceSyncRepository port is supplied by the same Android
runtime repository and private registry. It finds the exact logical owner/device/
operation, calls the existing native lease's first-sync operation, and checks the
same retained resource after I/O. Missing resources fail without opening another
SDK. Hosts without that binding return explicit unavailability.

The original runtime owns the SDK sync pump, persisted cursor, listener and shutdown.
Concurrent first-response waiters do not allocate another pump. Cancelling a caller
does not abandon native I/O; close deactivates callbacks and keeps the store until
the native request drains. SDK synchronization does not enable message queues.
The use case has one execution owner and a 15-second budget, checks current local
admission after suspended work, and preserves confirmed denial through failed
restriction persistence and cleanup. It calls no other use case.

Focused validation now passes 37 cases in 152.816 seconds: sixteen domain cases,
nineteen Android host cases and two app graph cases, with zero failures/errors/skips.
Exactly 4,599 source inputs and all Inspector bytes stayed unchanged; every current
source and fresh XML report/count is audited. Full required validation passes 4,340 fresh cases in 526.554 seconds: 3,695 desktop,
629 Android host and sixteen browser cases, with zero failures/errors/skips. Root
check, Android release compilation/lint, shared Wasm/iOS source and quality checks
pass. Focused/full source manifests match; Inspector inputs are unchanged.
[Recorded evidence](measurements/collaboration-space-device-sync-2026-11-09.json).
The domain cases and three additional Android cases exercise admission,
identity/gateway changes, expiry, concurrent/cancelled waiters,
group controls through the owned sync, and late shutdown. Native tests use inert FFI;
actual provider delivery is a separate acceptance step. The first candidate exposed
a missing mapper import and one outdated DI fixture capability; both are corrected.
Two new cleanup assertions were adjusted during review for group callback disposal,
without changing existing company helpers or tests.

This increment does not bind an application collector or organization recovery worker.
Those owners must subscribe before starting sync and independently discover retained
work on startup/reconnect. No product rollout, new APK or phone installation is
included merely by exposing this port.
