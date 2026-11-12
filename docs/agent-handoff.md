# Agent continuation — communications long run

Updated 12 November 2026, 20:45 UTC. Continue beyond checkpoints; push every commit.
All54 PRD acceptance groups remain OPEN. No subagents. One local compiler OR emulator.
Phone installs require a prior announcement, explicit serial and `--install-only`; never
launch/drive/reset/uninstall the user's phone UI. The physical iPhone interaction requirement
is waived; native Apple/call/push behavior is not accepted merely by compilation.

## Current next implementation

ACTIVE: canonical thread commands in Mobile.work/canonical-thread-commands-2026-11-12.
15 source paths change,5167 inputs: existing canonical observer evolves to
ObserveCollaborationSpaceThread, returning typed history plus separate Space/Original
command lists. No API/SDK/mutation or Company reads for Space-only rooms. Old history
assertions inspect the same nested component; no prior test IDs removed.
Format1/39167 REAPED PASS36.269s. Focused1/42217 REAPED234.510s,51 cases/2 failures:
42 domain cases pass; both new data failures assumed reopen retained the old local
session. Existing Scenario.reopen also renews login. Preserve failed-storage-fixture-1.
Tests now require the new access explicitly, retain all content comparisons, reject
old-session lookup and read originals through renewed Company access. Production was
not changed for those fixture failures. App DI2 did not run after the data failures.

Review found a separate concern: combine waits for all first values before propagating
a known failure. New deterministic case aKnownCommandFailureDoesNotWaitForAnotherCommandStore
holds the original source and expects the canonical failure immediately. It has not run.
Format2/88470 REAPED PASS35.045s. Red-fast-1/36525 ACTIVE from20:43; expect one
real failure with current production. Frozen5167inputs/4aac27ec2961a30229c6060ca037fed5d7726ee0e43ddb6d435c7004ed85ebb9. Preserve the red candidate, fix the read-state
composition only after evidence, keep all assertions, rerun focused expected54 and
full expected14724 with prior14688 IDs. No full/current native claim for these edits yet.
No source edits or second compiler/emulator until the active red compiler is reaped.

Company thread publication is ACCEPTED: Mobile151438b4ba85f599fb2eefc080a6ac15423ac6e1,
Group6158096d evidence (later Group1480acdf records hosted progress). Both pushed.
Workspace .work/company-thread-publication-2026-11-12: full95318 REAPED PASS668.332s,
14,688 cases =12,650 fresh +2,038 unchanged; all prior IDs retained. Native20256 REAPED
PASS10/476.311s on exact APK293446ec43495b4abb323cde31d1cc110b00a707fe38ecd346115ab8c92a928b.
Inputs5161/52aa82e138e376350b6f43a2e09fa33c757359c41f59b23bd619ac05f0609663.
Report2d942e3fa5cd90e46d74baaf60442c5c146f76e37e26e1102943a955e985a14a.
Preserve red1 (16cases/one real failure,3values instead of2), failed-publication-boundary-1,
and exact111 historical excluded XML/657 cases. Source/native acceptance is finished; do not rerun.

Release92 is fully ACCEPTED and UNINSTALLED; see Phone delivery below. No local heavy process
remains after ARM build39435 and verifier38284 REAPED. The release gates are finished; the new source formatter above is active.

NEXT: implement canonical bound-room pending-command projection in the existing observer.
Mobile.work/canonical-thread-commands-2026-11-12 has plan.json and a verified14688-case
baseline (1455 reports), current source manifest and historical native10. Source implementation and validation are active as recorded above.
Evolve ObserveCollaborationSpaceThreadHistory into ObserveCollaborationSpaceThread, keeping
CollaborationSpaceThreadHistory as the typed history component of a new thread snapshot.
Add separate Space/Original Company command lists; never coerce ownership or collapse equal
UUIDs across namespaces. Group admission still owns both; Space-only rooms perform no Company
message I/O. Reuse the current reader lifetime, validation policies and repositories, not a
second copied observer or usecase-to-usecase orchestration. Freeze staging baselines before
suspended admission checks, retain pending and sent-but-unarchived facts, retire all readers
on cancellation. Preserve all existing history test identities/assertions; add meaningful
command ownership, concurrency, immutable snapshot and actual encrypted reopen cases.
Unbound conversations/composer metadata remain a subsequent contract before product UI.
Do not stop at this checkpoint. Push every commit and continue the product long run.

Canonical history baseline is Mobileed4e61f5 / Group6c34ecbd, full14686 accepted; no rerun.
Inventory compact evidence cleanup is Mobile7f7fa717 / Groupa4ecdae3, already pushed.

## Current completed increment

Native account replacement is accepted in `.work/native-account-replacement-2026-11-12`
in Mobile and Group. Seven Mobile proof paths and three Group fixture/driver paths changed;
production code did not. The actual app lifetime retires A while its committed room-key reply
is held, publishes B, rejects B's read of A receipt, joins the released stale consumer with0 ACK,
restores A receipt and existing-only SDK identity, and preserves history in a new offline process.
Interactive activation and OS scheduling remain fixture controlled; no login UI/server-revocation claim.

Mobile inputs5,151 /70bd7258a94872ca0d7167eb691e1635fdde47facf480da41d9d1fc7515e4093.
Group inputs8,753 /7e4c32bb578f148fcbd21c62a8302436bcf1abfb534fc0b561aab9510642b300.
Proof2/10657 REAPED PASS21/114.780s; immutable proof-2/accepted-proof.apk152,433,161B/
76b4ba13de5f3dcb40303e8ce8822dfcae6fad6062bd1609dc8163645b019f6c. SDK8 native unchanged.
Group source2/92255 REAPED PASS78.803s; protocol70 pass. Native replacement2/78031 REAPED
PASS110.983s; suite2/41574 REAPED PASS10/481.291s, exact prior9 identities retained. Fresh
2560MiB/2core emulator stopped; no compiler overlap. New scenario has13 HRIS and32 Matrix
requests for the full online ceremony,1 receipt write,0 stale ACK and0 offline requests.
Full1/98401 REAPED PASS69.700s. Strict full audit accepts14,626 cases:21 current proof plus
14,605 exact unchanged production reports. Full check executes0 new cases. Report manifest
14938a42cab9332ae4740820e7e86697d2ceb08f5caea72b6f04616012c13202.

Preserve failed-static-candidate-1 (101-line method), failed-fixture-candidate-1 (wrong fictional
body prefix rejected before app preparation), and suite-1 launch-failure.json (TIME_WAIT5563,
no process started). No deadlines or assertions weakened. Sources/data from failed native runs
remain. `accept-native.py suite-2` and Mobile `accept-full.py full-1` have passed. Do not repeat.
Contract and compact evidence: native-account-replacement documentation and measurement.
The preceding account-replacement checkpoint is historical; release92 compiler state is above.

## Continue these exact hosted gates

Shared registry43266580439 on ddf65dbe485aff6f2ef74d22469a723892d04d93 is FULLY accepted:
12,604portable +5,135native iOS shared cases =17,739; prior IDs preserved, zero replacements.
Both artifact digests/CRC/reports/logs audited. Apple5135 does not include the four Swift SDK tests.
See measurements/collaboration-runtime-registry-platforms-2026-11-12.json. Do not poll/rerun it.

Inventory43273697021 ondd405660a3ea920a4f27781f006d83b7b7fd636a is FULLY ACCEPTED:
12,604portable +5,148native iOS =17,752. Appleauditor86983 REAPED PASS, all13inventory methods,
original case IDs, full host build and push fields verified. Appleartifact13977676010/812817B/
be8a2d32057e1bdd8cffd494957572f8e709fd673a33f95def7608c830e6f9f6;
reportefc3139e3e11249eca18cc3e0ad7c81dea534e35d9235bcb1c164bb194e36b27;
loge91af91423786ab3bfabc2351a7092696633806add4044c676bc337ee28b3f50.
Portableauditor13912 accepted12,604; Chrome156→157 changed only launcher suffix on5,601 old
cases. Failed auditor/audit-correction retained. Both compact measurements updated. Do not rerun.

Store43279731370 on836ac52575daf1747aac1cf94a6768a80998023f is FULLY ACCEPTED:
12,604portable +5,156native iOS =17,760. Portable42655 and Apple76810 REAPED; no rerun.
Appleartifact13981937933/815305B/b74647c86112a58ae525e4ea6d096458f20dfddae47680f50d8a1b2a53833cae;
report32203e2d29e44a9197a695b1506e4ae00ba0a059b4de03e5b46cecdb024d5aff;
log70d167799a99029438f15c02abdf579ceaedd6354ccf461200c70e85642a56e8.
All8 new store and13 unchanged inventory methods execute; prior IDs, host build and
push fields verified. Portableartifact13979103864/4047761B/46d6243020390461076118757d53c002d4ea33966db6a10908b48964b5493bd7.
Prepared config incorrectly copied a Chrome156→157 replacement; actual current browser
reports are156, matching the baseline directly. Failed config/auditor22498 and correction
retained; no case normalization, test rerun or source change. Both audits finished.

Control43297910558 on151438b4ba85f599fb2eefc080a6ac15423ac6e1 has STARTED:
portable131478498528 running, Apple131478499227 queued at20:44. Expected12,666portable
+5,187Apple =17,853. Mobile.work/company-thread-publication-2026-11-12/hosted-control
state and hosted-audit.py prepared; neither audit run. Do not dispatch multiple pending runs.
Inventory and store local main ARM64/metadata, all21 native-test method compilation and full
`:check` pass. Linux executed0 Apple cases. Both adapters require a pre-provisioned private,
backup-excluded root; store creation and existing-only access remain separate. No product binding.

Native SDK8 Apple43267867675 FAILED on the150-minute framework build deadline; top
section records exact failed artifact and measured target times. New isolated budget-only
candidate43296438399 on3c9c41a9f45f0533e4591a632d1df862668a06d0/job131455907436 is running.
Use Mobile.work/sdk8-apple-build-budget-2026-11-12/audit-hosted.py only after success.
All4 Swift identities,5 architectures/3 slices and device/simulator links remain required.
Two older ARM attempts43261761056 failed before any step due to runner capacity; preserve.
No SDK publication or Apple app runtime binding yet. The app still selects unavailable Apple
Matrix runtime; actual bridge/composition follows accepted native SDK8. Preserve failed evidence
and fix demonstrated causes only. Independent Android/Group product work may continue.

## Phone delivery

Release92 is fully accepted from151438b4ba85f599fb2eefc080a6ac15423ac6e1 and UNINSTALLED.
Mobile.work/release-phone-2026-11-12-build92/release-acceptance.json and measurement record
14688+10native, source5161/52aa82e1..., SDK26.10.23-hris.8, nondebuggable, same signer,
lint0, compiled backup exclusions,16KiB ZIP/native alignment and equal executable DEX:
722a63ed79391df34e89a36b072bc9ca712c2fe1902cbf36f5b8f31bb3a1a927.
ARM124333455B/d8001bd137fdd08d3d3b112519eef226ee38ba78c35c6b29cba51b019fc3700c.
x86138079688B/0742652c7382f37155bd7880aa1c2cd162ffaf88af7d9c089e750dcef6c42810.
X86 build17259 REAPED657.652s, verify61263 REAPED; startup53204 REAPED78.255s,
fresh2560MiB/two cores, cold2908ms/reopen2145ms, stable10s/process. Both screenshots
were inspected and show complete onboarding. ARM39435 REAPED42.972s, verify38284 REAPED.
All gates are finished; do not rebuild. No authenticated release/phone latency claim.

Latest ADB97763 REAPED timeout12s at20:26UTC; no installation attempted. At20:12UTC the
WireGuard handshake was34837s old; route is correct viawg0. Do not repeat availability questions.
When10.77.77.3:5555 connects, announce EACH actual installation, then run Mobile
.work/release-phone-2026-11-12-build92/install.py --install-only. Inspect any existing
install-result first; never uninstall/reset/downgrade/launch/drive the phone UI. Preserve app data.
92 supersedes uninstalled91/90. Last confirmed physical install89 at09:10UTC; never reinstall89.

## Protected repositories, stores and live runtime

Mobile branch feat/chat-send-timing-20261107. Group worktree
/home/fajar/hris/.worktrees/communication-space branch feat/communication-space.
Starter /home/fajar/hris/compose-fluent-starter badfc0d: clean/accepted906. HRIS policy/test fixture
changes are not template ports. Main Platform worktree feat/communications37a74bb9 is protected.
Inspector /home/fajar/hris/kmp-network-inspector f8ca3d6: preserve unrelated gradlew.bat.

SDK26.10.23-hris.8 is immutable: AAR SHA6eebe8dab124541783d2b1d746cf30ea4b8dd2a599980cccb823927780678717;
x86 native6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95;
ARM native27ad4f7c31fcca109aa0d7635b2e20e0e76a2a81bdbbfe0bf158f389ef274869.
Never rebuild/mutate/push protected SDK trees:
- /home/fajar/hris/.worktrees/matrix-sdk-incoming-hints-2026-11-10 (upstream8de215a +9accepted patches)
- /home/fajar/hris/.worktrees/matrix-kotlin-incoming-hints-2026-11-11 (c1664f7 +accepted libraries)
- /home/fajar/hris/.worktrees/hris-mobile-incoming-hints-consumer-2026-11-11 (b2aef56 +5accepted paths)
Legacy collaboration-hris.yml/check_space_queue_adoption.py use SDK4; never dispatch as SDK8 proof.

Live90163c32500ff2fa005013eaf83b026d4bdcf5ff, schema116.4, readiness ON/Group OFF,11key publications.
Group V117–140 is UNDEPLOYED. Do not replace live services from this worktree. Preserve Bima74
and stopped Mobile87 SDK stores, sidecars, keys, logical IDs and all failed userdata. Never send
Draft66 or replay accepted Control77/Warm77/Recovery78/Registry82/Parent83/History84/Selected86/Joined87.
Never print Firebase credentials. Server Java services are protected; do not kill them.

Use owned-heavy-processes.py from Platform .work/readiness-wake-2026-11-08 before heavy work.
One local compiler OR emulator; test JVM768MiB may accompany a precompiled emulator scenario.
Emulator2560MiB/2core, serialemulator-5562. Java21, SDK/home/fajar/Android/Sdk,
TMPDIR/home/fajar/hris/.work/build-tmp. Gradle no-daemon/no-parallel/one worker, Kotlin in-process.
Use setsid --wait; reap tool sessions, preserve failed input snapshots, and compare source/SDK hashes.

[Previous detailed checkpoint](agent-handoff-history-through-apple-registry-2026-11-12.md)
retains older artifacts, failure records and links. Current state above supersedes its pending statuses.
