# Apple SDK build budget continuation

## Isolated SDK8 Apple candidate — 13 November 2026, 02:03 UTC

This worktree owns only the native Apple builder correction. Run43327713661 is
FINISHED/FAILED on65e649d12375af02a359470789e26584ad26c292, job131564298006.
The pinned symbol reader and all five architecture/export checks passed; Swift
linking failed after8744.846s, with zero native test executions. Original archive
14001768615/306715428B/9ac07f4ed39b2b4fd4fbea96c1cf590c149562880039663dc79938ea25277e49
and job/build logs remain under Mobile.work/sdk8-apple-symbol-reader-2026-11-12.

The root cause is partial crate selection during upstream SQLite symbol localization:
Rust1.96 object names no longer match its legacy crate-hash parser. The build-only
patch keeps all Rust codegen units and allocator shims together. Existing SQLite
isolation, source pins, Rust version, five architectures and four Swift tests remain.
Mobile.work/sdk8-apple-crate-units-2026-11-13 contains the exact old candidate, failure
inventory, red3/green8 actual Rust tests and20 passing Python guards. No compiler
ran alongside the Mobile Gradle build; the short Rust test ran after focused2 reaped.
Commit/push this isolated candidate, then dispatch exactly one hosted SDK workflow.
Do not use the old success-only auditor without updating its exact head/job and new
privateCrateProbe evidence. No release publication or production Apple activation.
Main Mobile composer observation full1 is separately running; keep its sources frozen.


Isolated branch fix/apple-sdk-build-budget-20261112, based on Mobilee056b86a.
This checkout changes only the SDK Apple build driver, its CI budget and evidence.
The main Mobile checkout has an active source-exact native suite; do not edit its
sources, SDK checkouts or run a second local compiler/emulator.

Run43267867675 / job131356202237 at9b7c4d3f failed on the9000-second aggregate
framework deadline during target4, after target1/2/3 compiled in42m46s/37m37s/41m46s.
The preserved artifact13979116356 has SHA256
6d1a3b994c2a123359b0da700463a3743cc03c313126c907232098576eb4c0d8.
No Swift contract ran; this is not accepted native evidence.

The framework compilation budget is now300minutes and job360minutes. All five
architectures, pinned source/toolchain, sequential two-job build, four Swift tests,
30-minute Swift/device/simulator deadlines and exact binary acceptance stay intact.
Existing Python guard tests pass10 cases in0.222s. Commit/push, dispatch this workflow once,
then audit its exact source, artifact digest, all four Swift cases and five native
architectures. Keep failed evidence. Do not publish or bind an unaccepted package.
Afterward integrate the tooling/evidence change, retaining the main branch handoff, when its
source-exact native/release work is complete. Continue the product long run there.

## Parent checkpoint retained for context

# Agent continuation — communications long run

Updated 12 November 2026, 19:43 UTC. Continue beyond checkpoints; push every commit.
All54 PRD acceptance groups remain OPEN. No subagents. One local compiler OR emulator.
Phone installs require a prior announcement, explicit serial and `--install-only`; never
launch/drive/reset/uninstall the user's phone UI. The physical iPhone interaction requirement
is waived; native Apple/call/push behavior is not accepted merely by compilation.

## Current next implementation

ACTIVE: .work/company-thread-publication-2026-11-12, base Mobileed4e61f5 / Group6c34ecbd.
One new Company slow-consumer regression is the only source change; production is untouched.
Formatter1/8189 REAPED PASS35.369s. Red1/52219 ACTIVE from19:42UTC; expect16 cases with the
new denial-buffering case failing. Reap before edits/new compiler/emulator. Frozen5161inputs/
e82199b1009c8ba034cf0aee75d1720fd3257111d06266ebf9bd1173211a1f8d.
Preserve exact failing candidate before a demonstrated fix. Do not mark the work complete here.


Canonical local Space thread history is ACCEPTED and pushed as Mobileed4e61f5 / Group6c34ecbd in Mobile
.work/space-thread-history-routing-2026-11-12. Read docs/collaboration-space-thread-history.md.
13 changed inputs add typed physical location and one owned observation use case, data DI and
actual encrypted storage integration. Original Company coordinates remain under Group admission;
HR Company selection/denial does not replace organization authority. Product UI remains separate.

Focused3/74590 REAPED PASS36/273.041s. Full1/7777 REAPED PASS822.013s; strict audit accepts14,686:
12,648 fresh and2,038 unchanged cases, all14,626 prior identities preserved,60 new across JVM/browser.
Inputs5161/cd6f263a89da0b2a974501b099286b8884a66c7d19b2de2b9680cdc74165f35f.
Report manifest61c2a871b3500217b05c5b2fbc35c1d5d168a5e5c84734f441cf283dd14161eb.
Immutable full-1/accepted-proof.apk152443503B/
0bbdd271e6bdb09064d27e720d11a51479a4a124b0072765b3e33adb843435f6; SDK8 native unchanged.
No new native suite or phone claim. Canonical-history gates are finished; the Company compiler above is active. Do not rerun accepted canonical gates.

Preserve failed-test-compile-1, failed-publication-boundary-1 and full-1/failed-auditor-1.py.
The exact slow-consumer test demonstrated an extra outer buffer holding a page outside cancellation;
removing only that extra handoff fixes it. Assertions retained. Audit excluded111 old browser XML
files/657 historical cases outside both accepted baseline and current execution; no accepted IDs
removed, no test rerun for the audit correction. Digests and audit-correction.json are preserved.

NEXT: independently reproduce the analogous Company ObserveCollaborationThread boundary before
changing it. .work/space-thread-history-routing-2026-11-12/company-publication-followup.md explains
it and company-publication-regression.kt is a prepared, uninstalled test snippet. Keep current parallel
context/history/queue startup. After this regression, continue canonical pending-message projection
and product routing. Do not stop at this checkpoint; push every accepted commit.

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
No compiler/emulator is active at this checkpoint. No new phone APK for proof-only changes.

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

Store43279731370 on836ac52575daf1747aac1cf94a6768a80998023f has STARTED.
Portable131439574875 and Apple131439575160 are running; latest19:42UTC.
Dispatch89306 REAPED; expect12,604portable and5,156Apple (prior13 inventory +8 store methods).
Run `.work/apple-sdk-store-2026-11-12/hosted-audit.py portable|apple hosted-43279731370` only
after each job succeeds. Auditor is prepared/syntax checked, not executed. A later same-branch run
can now queue; finish the Company fix before choosing its exact head. Do not dispatch multiple pending runs.
Inventory and store local main ARM64/metadata, all21 native-test method compilation and full
`:check` pass. Linux executed0 Apple cases. Both adapters require a pre-provisioned private,
backup-excluded root; store creation and existing-only access remain separate. No product binding.

Native SDK8 Apple candidate43267867675 on9b7c4d3fac0127e2f440e8dc5fb420dc66c22cfc is
IN_PROGRESS on macos-15-intel, job131356202237 (build began17:18UTC). It must verify all4 Swift
cases, original raw-key test unchanged,5 architectures across3 XCFramework slices and actual
iOS device+simulator linking. Run `.work/sdk8-apple-planning-2026-11-12/audit-hosted.py` after
success; prepared/syntax checked, not executed. Two earlier ARM attempts43261761056 failed
before any step due to runner acquisition/capacity; exact annotations preserved. Do not rerun ARM.
No SDK publication or Apple app runtime binding yet. The app still selects unavailable Apple
Matrix runtime; actual bridge/composition follows accepted native SDK8. Preserve failed evidence
and fix demonstrated causes only. Independent Android/Group product work may continue.

## Phone delivery

Release91 is fully accepted and UNINSTALLED, from b816431824e4255fcc101641bc6257894ff6649e.
The exact source record is in `.work/release-phone-2026-11-12-build91/release-acceptance.json`
and measurements/collaboration-release91-2026-11-12.json. Workspace accepted14,626+9native inputs,
release lint0, same signer/appID, SDK26.10.23-hris.8, nondebuggable,16KiB ZIP/ELF, equal DEX in both ABIs.
ARM124,317,071B /45e26962b2acc839498558affd2fbce059f602f29d1b62e25f2b55276740cf7d.
Fresh x86 emulator cold2891ms/reopen1557ms, each stable10s, actual onboarding screenshot inspected.
Release91 supersedes uninstalled90;89 installed09:10UTC earlier. Never reinstall89.

Latest connect87876 REAPED timeout12s at19:22UTC (also75528 at19:03 and50256 at18:59); no install attempted.
Route is10.77.77.3 viawg0. At19:16UTC the peer handshake was31,445s old. Do not repeatedly
ask for availability. Once explicit ADB serial10.77.77.3:5555 connects, announce EACH install,
then `setsid --wait python3 .work/release-phone-2026-11-12-build91/install.py --install-only`.
Inspect any existing install-result first, preserve signer/identity and verify exact APK bytes.
Never drive the phone UI; continue other work while unavailable. Firebase IAM is already verified.

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
