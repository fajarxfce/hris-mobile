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
The original plan above is retained; its accepted result and limits follow below.

## Accepted controlled result — 7 November 2026

The narrow scenario is now implemented and has passed. The actual SDK4/application
worker sent the ready room's original message once while the first room received a
real `403 collaboration_recipient_unavailable`. Both rooms had READY metadata. SQL
confirmed sender/ready-recipient key publication and no publication for the other
recipient. The ready recipient's timeline and SDK runtime were closed before either
command was queued; its native parent/device were not revoked.

The ready command's queue call returned 1,316 ms before the local Sent observation,
1,321 ms before the separate journal read and 1,815 ms before immutable completion
was observed. Those are observation bounds, not exact database commit timestamps.
The server handled the unavailable send in 34 ms and the accepted send in 110 ms;
server/device clocks are never subtracted. The accepted event was independently read
through the gateway and verified as ciphertext. The unavailable original command and
its identity remain retained, with no receipt or fabricated completion.

This **did not reproduce** the reported 20-second to 2-minute phone delay. The sender
runtime was already initialized and syncing. It establishes neither cold-start nor
physical-phone latency and justifies no timeout, retry or writer-policy change.
It does demonstrate delivery while the ready recipient's SDK is closed. The latest
passive phone53 log at 08:38:32 UTC still contains no post-install diagnostics; silence
is not successful delivery. Keep the existing optional feedback request pending.

`HrisMixedRoomProbe` owns its resources, actual foreground delivery worker and local
observations. `HrisWorkerProofContainer` accepts an explicit conversation for fixture
queue/cache reads so both rooms share the same application container. Platform's
`NativeMixedRoomDeliveryTest`, `NativeMixedRoomTrace` and
`tool/collaboration_mixed_room_native.py` own the loopback server, finite readiness
handshakes, original HTTP observations and emulator-only driver. No product caller
or production policy changed; this HRIS-specific fixture has no Starter port.

Local root/Android/format/architecture/Detekt passed in 134.102 seconds: 20 fresh
cases, 4,325 unchanged inputs, zero failures/errors/skips and lint errors; proof lint
retains 49 existing warnings. Backend test compilation/format/architecture passed in
31.020 seconds after a retained test-only missing-encoder compile error. Four driver
validator cases passed. The actual native test passed in 149.551 seconds including
fresh emulator startup, with one JUnit case and no skips. SDK4 hashes stayed exact;
the original APK and all frozen source inputs were unchanged. Emulator process and
transport are stopped. [Hash-bound measurement](measurements/collaboration-mixed-room-delivery-2026-11-07.json).

## Continuation

This investigation continues alongside the organization migration work. Original-room
sending still requires current recipient admission and a published group room binding;
history access or a local queue match alone grants neither. Preserve V132 canonical
pair routing, original crypto stores and all pending identities. Continue original
delivery/history publication, native-parent recovery, product routing and all 54 PRD
groups after the experiment.
