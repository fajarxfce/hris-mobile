# Pending-message investigation: mixed recipient readiness

The physical-phone latency report remains open. Bima Demo has an active device and
published keys; he need not keep the application open for server acceptance. Earlier
phone52 traces matched a successful request to Bima and repeated recipient-unavailable
rejections to another owned room. Release53 removes foreground dependence on an older
WorkManager retry chain, but its synthetic wake measurement does not establish actual
message latency. The optional phone53 feedback request is already pending.

The next controlled experiment uses one sender with two rooms: a READY room whose
recipient has no eligible key-published device, and a room with a ready test recipient.
Use a fresh, isolated API/PostgreSQL/Synapse/SDK4 fixture and the actual application
worker. Keep an original pending command in the first room, then append one new durable
command to the second. Confirm the readiness preconditions through the fixture's
repositories without changing production permission guards.

Record queue publication, native attempt, actual server acceptance, durable receipt
and observable sent state separately. Determine whether a native retry outlives its
12-second room waiter, holds the existing writer, delays the next cursor entry, or
leaves an observed receipt waiting for a later projection pass. Compare monotonic
timestamps within their own process; do not subtract unrelated host/device clocks.
An absent native transaction or a quiet log does not establish delivery.

`NativeCollaborationWorkerInteropTest` and `HrisWorkerMatrixProbe` show the existing
fixture/container contracts, but their accepted 175-message history scenario should
not be repeated merely for timing. Add a narrow separate scenario if needed, building
both sides before starting the dedicated emulator. Keep original message IDs and all
failed evidence. The user's phone and original Bima data are outside this fixture.

No new timeout, priority or writer-exclusion change is justified yet. Own-device and
READY-room metadata currently precede SDK sending; those facts alone do not establish
recipient key readiness. A reproduced issue should be fixed in the owning policy or
orchestration layer, with raw I/O in datasources and presentation in ViewModels.
No new reproduction source or result is accepted yet.

This investigation continues alongside the organization migration work. Original-room
sending still requires current recipient admission and a published group room binding;
history access or a local queue match alone grants neither. Preserve V132 canonical
pair routing, original crypto stores and all pending identities. Continue original
delivery/history publication, native-parent recovery, product routing and all 54 PRD
groups after the experiment.
