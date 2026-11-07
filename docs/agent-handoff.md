# Agent continuation — HRIS Mobile

Read [architecture](architecture.md), the linked contracts and this checkpoint before
continuing. [Previous checkpoint](https://github.com/fajarxfce/hris-mobile/blob/99048061/docs/agent-handoff.md)
retains all earlier source/CI history and links older local handoff archives. Git and
contracts are authoritative; `.work` is staging/evidence, never permission to reapply.

## Current accepted work — 7 November 2026

Owning original Room resolution is locally accepted: nine Kotlin source/test files.
It reads terminal facts locally, rejects Pending, retains the original intent/attempt
count, marks normalized durable state before one finite current-native resolution,
and captures observed receipts/fences with a2s cancellation budget. Global denial
survives failed persistence; peer denial does not close other contacts. No old-device
login, request() replay, archive, successor, SDK action or product caller is added.
Focused21909 passed91 cases/219.787s; full28463 passed3,755 fresh cases/460 suites/
525.629s on4,405 unchanged inputs, zero failures/errors/skips/lint. Root/Android host/
quality/shared Wasm and64 explicitly verified Browser cases passed. Actual22 domain
and4 storage cases ran on Desktop/Browser; Apple wrappers remain for source CI.
All local jobs are reaped. [Contract/results](collaboration-space-room-request-resolution.md).

Durable Room-only Fenced storage fc0034ed is accepted/pushed to recovery. It preserves
v1 request bytes/keys/old outcomes, uses schema2 only for new fences, and retains
terminal CAS without successor/archive permission. Focused65/217.124s; full3,697/455/
513.19s on4,398 unchanged inputs passed. Audit caught zero selected Browser policy
cases; supplemental82617 ran all6/26.512s on identical source. All failed/missing
selection evidence and successful reports are retained. [Storage](collaboration-space-room-fence-storage.md).
Typed fence transport fe3992f1 previously passed44 focused and3,671 full cases.
[Transport](collaboration-space-room-request-fence.md). Its candidate copies are historical.

Earlier Open replay335e3914, preparation1d9493df, canonical metadata aa700e37,
observation3b3a5d81, original command storagec8c952af and stable Room transport79b41269
remain accepted. Open's actual normalized-persistence CAS regression remains tested.
Their source contracts and exact results are linked by architecture and prior checkpoint.

## Next work

1. Commit/push accepted owning resolution, then ordinary fast-forward primary promotion.
   Primary was fe3992f1426712bf23964bf5aea68ad382f1b624; its first control42380244144 and
   native42380243983 have BOTH STARTED. The pending-first-run barrier is clear for this
   promotion. Record/preserve the next first control/native IDs before another primary
   promotion; recovery source/doc work can continue without replacing pending runs.
2. Implement [atomic original/successor storage](collaboration-space-room-successor-plan.md)
   using existing raw archiveAndInsert, authenticated forward/back references and unchanged
   original ciphertext. NINE production/fixture candidates are isolated in
   `.work/space-room-successor-2026-11-07`, with mapping/baseline hashes. They are NOT
   APPLIED/TESTED and lack the planned policy/real-storage behavioral cases. Add those
   cases, review the design, verify baselines, then apply/format and run focused/full
   gates with explicitly verified Browser selection. Do not assume candidates are accepted.
3. After atomic storage, implement current-admitted owning Room preparation/dispatch,
   projection/recovery, original SDK delivery/history, native-parent recovery and product
   routing. A setup receipt/fence is not a message ACK or content authority. New offline
   chats also need provisional peer/Open mapping without replacing original message IDs.
4. Continue all54 PRD groups:3 in progress/51 planned/none complete. Physical iPhone
   testing is waived; relevant Apple compilation/CI is still required. Validate reusable
   foundations in Mobile before Starter ports. No HRIS-specific policy port is needed here.

## Original first CI

First Open source31e618cc control42376025279 and native42376025306 are fully audited
SUCCESS. Apple2,056/232 and portable4,979/550 have zero failures/errors/skips; actual25
Open replay plus3 real-storage cases on each Apple/Desktop/Browser are verified.
[Control artifacts](measurements/collaboration-space-conversation-open-control-ci-2026-11-07.json).
Native artifacts13578122304 ARM/13577574594 x86 contain543/86 each, zero failures/errors/
skips. Exact immutableSDK4 hashes/NDK, keys/IDs/process death and cleanup pass. Original
queue20.779s x86/74.454s ARM sends zero provider messages; separate six HRIS scenarios
retain exact send counts[1,3,3,176,176,176]. Inapplicable false flags remain explicit.
[Native artifacts](measurements/collaboration-space-conversation-open-native-ci-2026-11-07.json).
Audits81220/32358 are reaped; no provider reruns. This older source does not replace
first source CI for fence storage or owning resolution. Prior1d/3b3/79/fb/e8 first CI
is fully accepted, with exact original artifacts retained in the previous checkpoint.

## Other repositories and live state

Group branch feat/communication-space is2a841ce3 (docs checkpoint), native dcdaf220
and HTTP cf885a1c accepted/pushed. Focused195/319.052s and API/FULL worker241/540.839s
pass with one existing opt-in retention skip. Corrected HTTP99/202.31s on8,637 stable
inputs passes; initial wrong fixture expectation is retained. V138 storage is c6937843.
All Group jobs are reaped. Main branch feat/communications is06dc66fb (docs checkpoint).
Starter feat/communications remainsb11437a. Update their handoffs after this source commit.

Live API2e75fe1ee88b5766259fc3efd3ab7f21e70d621a, worker10485916 and schema116.1 remain.
V117–138 and organization routing are unshipped/disabled. No live rollout or APK is
added by this source. Do not repeat accepted deployments/provider scenarios for evidence.

## Original SDK and phone invariants

Preserve `.work/demo-chat-device-2026-11-05/bima/userdata.img`, every sidecar/private key,
original pending transaction ID and failed native fixture. No reset/re-enrollment,
new physical store or fake ACK. Logical organization device and original physical
message device differ. One exclusive paused existing writer validates the COMPLETE
bounded128 inventory; no partial/changed resume or implicit join/sync/enqueue. Current
canonical room/recipient admission still precedes sends. [Writer](collaboration-space-original-writer.md).

Immutable SDK4 tag matrix-sdk-26.10.23-hris.4 / release437093395,
target4228599ba659cf68cfea1138e6f8017b0b77c222; never rebuild/republish.
AAR SHA2569b6806de02b7d40b8487b54a51b192c114a38c68c6dfe35c68faf50d3b6bfd21.
x86 .so55bde5be2080c3e42a2ece6053f94231d27165cc39698c89c50e36ecbdb2909a;
ARM .so5df0419b7f1a5dd5d934b0cce3846a439812581e35f86defe3c2098f29260d34.
NDK28.2.13676358; frozen SDK worktrees and original provenance remain in history.

Phone10.77.77.3:5555, dev.fajar.hris.mobile.dev, UID10962 (also1010962), release53
source08545820c0b58f46d784971e71ceb207dd628c0f remains installed since02:52:45.968748UTC.
ARM64 devRelease1.0.0/nondebuggable/noR8,121022760 bytes,
SHA256994d161ade3455745157d0793475b5091d67e968af909bda7a282dce163152c6.
No54 exists. Announce each validated feature install, explicit serial, --install-only;
never launch/drive the user's UI while in use. Use the owned emulator for interaction.

Physical20s–2min pending remains OPEN. ADB retries10:10/11:08/11:41UTC timed out; no
more ADB attempts this turn or repeated feedback question.08:38 passive audit had
zero postinstall lines; silence is not acceptance. Fresh read-only14:00/14:04 audits
confirm Bima has keys and one eligible confirmed recipient despite expired interactive
access. Earlier exact phone52 room matching shows BimaHTTP200 and4 HTTP403 for another
owned room with no recipient device. Neither finding proves the cause or fix for the
phone's latency. [Bima evidence](https://github.com/fajarxfce/hris-platform/blob/4fad6bb3/docs/measurements/collaboration-bima-recipient-keys-2026-11-07.json).

One heavy job only; no compiler/emulator overlap. Freeze source hashes, reap each job,
then edit. JDK21, one Gradle worker, strict dependencies and prepared bounded scripts.
Push every atomic implementation/test/doc commit, no co-author trailers. User authorizes
continued long-run work; do not stop at a partial feature or claim all54 groups complete.
