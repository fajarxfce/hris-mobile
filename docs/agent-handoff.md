# Agent continuation — HRIS Mobile

Read [architecture](architecture.md), the linked contracts and this checkpoint before
continuing. [Previous checkpoint](https://github.com/fajarxfce/hris-mobile/blob/99048061/docs/agent-handoff.md)
retains all earlier source/CI history and links older local handoff archives. Git and
contracts are authoritative; `.work` is staging/evidence, never permission to reapply.

## Current accepted work — 7 November 2026

The five-file remote-echo correlation fix is now locally accepted: bounded unsigned
transaction metadata reaches the Android mapper, with an exact durable Sent event-ID
fallback when transaction metadata is absent. Original ownership/device/sender/logical
checks remain; no fake ACK, queue mutation or SDK change. Focused74451 passed33/3/
157.361s; full83806 passed2,343 fresh cases/319 suites/519.474s on4,412 unchanged inputs,
zero failures/errors/skips/lint. Root/Android/quality/shared Wasm and33 actual Browser
cases passed. All jobs reaped. [Contract](collaboration-chat-remote-echo.md). Actual
phone/emulator confirmation still needed; build54 does not exist yet.



Pending chat-list preview is accepted in this commit: eleven source/test files share
one account command subscription, combine the latest original sends with local history
and drafts, and render compact clock/check icons. Frozen submission is not mislabelled
as an editable draft. Focused74307 passed50/7/266.430s; full95262 passed3,760 fresh cases/
458 suites/514.213s on4,412 unchanged inputs, zero failures/errors/skips/lint. Root,
Android host/quality/shared Wasm and20 actual Browser cases pass; three synthetic
small/dark200%/landscape captures were opened. All jobs reaped. [Contract](chat-inbox-previews.md).
No new APK yet; global recency cursor/recipient read receipts are unchanged.

Owning Room preparation is locally accepted in this source commit: seven source/test
files recover original work before current native room/device reads, preserve explicit
retry account/revision and perform one insertion or exact known-link atomic handoff.
Focused93110 passed128 cases/224.652s with4,410 stable inputs. One compiler-reported
unnecessary safe call was removed afterward; first hashes remain separately recorded.
Final full61602 passed3,829 fresh cases/462 suites/581.105s on4,410 unchanged final inputs,
zero failures/errors/skips/lint. Root, Android host/release lint, quality/shared Wasm and
101 explicitly verified Browser cases pass. Actual23 domain preparation and7 combined
resolution/preparation SQLite/IndexedDB cases run on Desktop/Browser. All jobs reaped.
[Contract/results](collaboration-space-room-preparation.md). No SDK/product caller/APK.

Atomic Room handoff is locally accepted: eleven source/test files, exact original
ciphertext, immutable archive successor and replacement predecessor, existing raw
archiveAndInsert and unchanged shared capacity128. New replacements cannot be inserted
independently. Focused40440 passed102 cases/216.321s; full45951 passed3,777 fresh cases/
460 suites/511.466s on4,406 unchanged inputs, zero failures/errors/skips/lint. Root,
Android host/release lint, quality/shared Wasm and75 explicitly verified Browser cases
passed. Actual30 command-storage and8 policy cases ran on Desktop/Browser. All local
jobs are reaped. [Contract/results](collaboration-space-room-successor.md). No owning
successor preparation, SDK operation, product caller or new APK is added.

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

1. Preview f76f5f50 is committed/pushed to recovery. Commit/push this accepted echo change. Primary currently
   c760c6c7; preserve its first native42387719927 (still pending) before promotion.
   Its control42387719940 has started. First0d control42383612502 succeeded (audit
   pending); native42383612567 still runs. Previous firstfe is fully accepted below.
2. Build54 from the accepted preview+echo source, then verify actual sending, pending/draft list previews and keyboard/composer visibility on the original owned emulator.
   User explicitly authorized sending our own test chats. ORIGINAL Bima emulator
   sent ONE real message to Karyawan Demo on the exact phone53 APK at15:49:26.104527UTC.
   Polling first saw Pending5.534s and Sent7.799s; these are observation bounds, not
   exact ACK timings. The actual recording shows a transient duplicated outgoing
   bubble during remote-echo reconciliation. No keyboard was visible in this first
   recording; do not claim the reported keyboard/viewport problem was reproduced.
   No further user test question is needed. Source clue: Android mapper uses the
   exclusive EventOrTransactionId enum, while matchesOwnedEcho and its synthetic
   test fixture assume transactionId is retained on a remote EventId. Inspect the
   existing bounded JSON's unsigned.transaction_id and exact durable Sent event ID;
   preserve sender/owner/room/logical checks and never fabricate ACKs or new IDs.
   Then validate and install build54 (none exists yet).
3. Continue the requested Chucker-like inspector including Matrix Rust traffic and
   CROSS-PLATFORM support in the new separate reusable repository:
   https://github.com/fajarxfce/kmp-network-inspector (private), local sibling directory.
   Main5fa5a21 is a pushed docs/design baseline. Uncommitted Gradle scaffold plus five
   Kotlin source/test files implement an initial metadata-only bounded recorder;
   thirteen common tests are WRITTEN BUT NOT RUN. No adapters/viewer/release/integration.
   Read its own AGENTS/design/handoff; .work/core-2026-11-07/status.md has candidate state.
   Do not start its compiler while the mobile build or owned emulator is running.
   Research only: KtorScope targets Android/iOS; KmpInspector has external records
   but Android/iOS/JVM only; Kick has Wasm but its network Logger is internal and
   its stock plugin stores raw headers/bodies and reads full responses before truncation.
   SDK4 already emits HTTP tracing spans, but has no exposed FFI HTTP observer.
   Proper Matrix instrumentation may need a NEW immutable SDK version. Never edit
   or republish SDK4. The initial core candidate is unvalidated; Ktor/Matrix instrumentation and viewer do not exist yet.
4. Continue original Room dispatch after accepted preparationc760c6c7. Preserve22
   original resolver cases and normalized marks/full CAS/finite observed capture.
   Design-only .work/space-room-execution-2026-11-07/plan.md is NOT applied source.
   Then published binding/projection, original SDK delivery/history, native-parent
   recovery, product routing and all54 PRD groups:3 in progress/51 planned/none complete.
   These complaints are additive, not cancellation of long-run work. Validate reusable
   foundations in Mobile before Starter; physical iPhone tests waived, Apple CI required.

No local heavy job remains. Original emulator5562/PID295446 was stopped at15:56:48UTC,
original userdata/sidecars preserved. .work/phone53-self-send-2026-11-07 retains the
private recording/logs and inactive pre-send backup: EVIDENCE ONLY, never restore it
after the send or run another SDK store from it. No phone UI was touched. New bounded
ADB connect31740 at15:41UTC timed out/reaped; do not repeat connection/feedback loops.
Do not attribute concurrent gateway403 endpoint_unavailable logs or all HRIS calls to
this send without route/correlation proof. Send latency remains open.

## Original first CI

First fence transport sourcefe3992f1 control42380244144/native42380243983 are fully
accepted. Apple2,064/233 and portable4,995/552 have zero failures/errors/skips; actual8
fence/25 Open/3 storage cases per Apple/Desktop/Browser and iOS host build pass.
Each native ABI551/87 has zero failures/errors/skips, including8 Android host fence
cases. ARM artifact13579853284/x8613579720811 preserve exactSDK4 hashes, keys/IDs,
process death and cleanup. Synthetic queue17.969s x86/77.257s ARM sends zero; separate
six HRIS scenarios retain exact[1,3,3,176,176,176] sends and inapplicable false flags.
Audits53739/52588 are reaped; no provider reruns. [Control](measurements/collaboration-space-room-fence-control-ci-2026-11-07.json)
and [native](measurements/collaboration-space-room-fence-native-ci-2026-11-07.json).
This source does not include durable Fenced/owning resolution/atomic/preparation work;
first0d source CI and the next promoted source remain separate.

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

Group branch feat/communication-space is28651f50 (docs checkpoint), native dcdaf220
and HTTP cf885a1c accepted/pushed. Focused195/319.052s and API/FULL worker241/540.839s
pass with one existing opt-in retention skip. Corrected HTTP99/202.31s on8,637 stable
inputs passes; initial wrong fixture expectation is retained. V138 storage is c6937843.
All Group jobs are reaped. Main branch feat/communications is19be24ce (docs checkpoint).
Starter feat/communications remainsb11437a. Their delivery.json next lists now point at current handoffs instead of obsolete jobs.
Update cross-repository handoffs after this source commit/promotion.

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
