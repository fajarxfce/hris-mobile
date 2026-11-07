# Agent continuation — HRIS Mobile

Read [architecture](architecture.md), the linked contracts and this checkpoint before
continuing. [Previous checkpoint](https://github.com/fajarxfce/hris-mobile/blob/99048061/docs/agent-handoff.md)
retains all earlier source/CI history and links older local handoff archives. Git and
contracts are authoritative; `.work` is staging/evidence, never permission to reapply.

## Current device findings — 7 November 2026, 16:59 UTC

Build54 source700a8a210908e90b53f6f65b4c672795d553aa6c compiled in659.185s;
strict dependencies, signing, zero lint, exact immutableSDK4, ARM64/16KiB and source
checks pass. Recovery and primary feat/communications are both pushed at700a8a21.
It is **NOT release-accepted or installed on the phone**. The owned original Bima
emulator was updated in place, with two explicitly authorized real test sends.
Pending preview survives offline/cold process restart; a new draft appears in the
list and restores to the composer after cold offline restart. Four actual screenshots
were opened. Online send was observed Pending2.769s and Sent4.857s (coarse bounds).

The same offline send failed to ACK within90s after network restoration. A separate
probe only sent the app Home and brought it back, without force-stop/new send. It
then ACKed by11.010s, but showed TWO outgoing copies at8.929s. The initial failed
probe remains intact; do not rewrite it as passed. Composer disabling during local
save also dismisses the software keyboard. [Sanitized evidence](measurements/collaboration-release54-probe-2026-11-07.json).
AppViewModel refreshes workspace once on foreground and does not retry the transient
failure; Android foreground worker currently owns only chat delivery. Exact failure
classification still needs validation. Do not weaken current authorization gates.

Original emulator PID705779/startTicks174661775 was stopped16:56:38.666036UTC; original
Bima userdata/sidecars/keys remain. It now holds54 and both actual sends, so future
install scripts must expect54. No restore/clone/re-enrollment or repeated test sends.
Phone still53; no ADB/phone UI operation was performed in this54 probe.

## Current accepted work — 7 November 2026

The five-file remote-echo correlation fix is now locally accepted: bounded unsigned
transaction metadata reaches the Android mapper, with an exact durable Sent event-ID
fallback when transaction metadata is absent. Original ownership/device/sender/logical
checks remain; no fake ACK, queue mutation or SDK change. Focused74451 passed33/3/
157.361s; full83806 passed2,343 fresh cases/319 suites/519.474s on4,412 unchanged inputs,
zero failures/errors/skips/lint. Root/Android/quality/shared Wasm and33 actual Browser
cases passed. All jobs reaped. [Contract](collaboration-chat-remote-echo.md). Actual
54 recovery revealed the remaining cases above; these source-only gates do not close them.



Pending chat-list preview is accepted in this commit: eleven source/test files share
one account command subscription, combine the latest original sends with local history
and drafts, and render compact clock/check icons. Frozen submission is not mislabelled
as an editable draft. Focused74307 passed50/7/266.430s; full95262 passed3,760 fresh cases/
458 suites/514.213s on4,412 unchanged inputs, zero failures/errors/skips/lint. Root,
Android host/quality/shared Wasm and20 actual Browser cases pass; three synthetic
small/dark200%/landscape captures were opened. All jobs reaped. [Contract](chat-inbox-previews.md).
Build54 contains this source; global recency cursor/recipient read receipts are unchanged.

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

1. Fix/verify the original54 offline recovery, echo and keyboard cases above. Preserve
   .work/release-phone-2026-11-07-build54 and its failed probe. No54 acceptance/install
   record exists. Actual videos are retained privately but not yet reviewed. Continue
   on recovery, test each source change, then build the next version and test the
   original emulator. Announce validated phone installs and use --install-only.
2. Preserve first700 control42392876943/native42392876947 (both pending when queried).
   Do not promote another primary source until both start. Priorc760 control42387719940
   and native42387719927 have started. First0d control42383612502 and native42383612567
   both succeeded; artifacts still need audit. Firstfe artifacts are fully accepted below.
3. The separate reusable inspector repo https://github.com/fajarxfce/kmp-network-inspector
   is private, main5fa5a21 docs baseline. Core candidate has14 tests, Android/JVM/iOS/Wasm
   targets and CI draft; no transport adapters/viewer/release/app integration. Bootstrap
   dependency job56466 PASSED/REAPED in31.758s with16 frozen inputs.210 components match
   existing Mobile verification;12 known artifact hashes supplemented; only two Gradle
   plugin markers are new. First strict52497 failed before tests because the default Yarn plugin added a project
   repository under FAIL_ON_PROJECT_REPOS; it is reaped. Switched to npm like Mobile;
   strict97570 now runs. Do not overlap a Mobile compiler/emulator. Read its own AGENTS/design/handoff before edits. Real Matrix Rust
   HTTP observation requires a new immutable SDK version; SDK4 remains untouched.
4. Continue original Room dispatch after accepted preparationc760c6c7. Preserve22
   resolution regressions, exact pending/uncertain original intent, finite capture and
   explicit fenced-only successor authority. Then room binding, existing SDK runtime
   recovery, product routing and all54 PRD groups:3 in progress/51 planned/none complete.
   These complaints are additive. Validate reusable changes in Mobile before Starter;
   physical iPhone tests are waived, relevant Apple CI remains required.

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
Build54 exists but failed recovery acceptance above. Announce each validated feature install, explicit serial, --install-only;
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
