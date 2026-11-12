# Agent continuation — communications long run

Updated 12 November 2026, 18:54 UTC. Continue beyond checkpoints; push every commit.
All54 PRD acceptance groups remain OPEN. No subagents. One local compiler OR emulator.
Phone installs require a prior announcement, explicit serial and `--install-only`; never
launch/drive/reset/uninstall the user's phone UI. The physical iPhone interaction requirement
is waived; native Apple/call/push behavior is not accepted merely by compilation.

## Current next implementation

Mobile .work/space-thread-history-routing-2026-11-12 is prepared from accepted312f294d.
Read Mobile docs/collaboration-space-thread-history-plan.md. Review found no Space-aware presentation;
existing Company and Space history observers remain separate. Implement typed local canonical-history
selection with actual Original coordinates and separate Company/Space admission before wiring UI.
No production code edited yet; no compiler/emulator active. Accepted native account proof is pushed:
Mobile312f294d, Groupfdbb8efd (60283/99233 REAPED). Continue work beyond this checkpoint.

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

Inventory43273697021 on dd405660a3ea920a4f27781f006d83b7b7fd636a is IN_PROGRESS.
Portable131394617779 is accepted12,604 (auditor13912 REAPED). Chrome156→157 changed only the
launcher suffix on5,601 prior browser cases; exact methods/classes/platforms preserved. Failed auditor
and audit-correction.json retained. Do not repeat portable. Apple131394617992 still runs; expect5,148 including
exact13 inventory methods. After each successful job run Mobile
`.work/apple-sdk-inventory-2026-11-12/hosted-audit.py portable|apple hosted-43273697021`.

Store43279731370 on836ac52575daf1747aac1cf94a6768a80998023f is PENDING behind inventory.
Dispatch89306 REAPED; expect12,604portable and5,156Apple (prior13 inventory +8 store methods).
Run `.work/apple-sdk-store-2026-11-12/hosted-audit.py portable|apple hosted-43279731370` only
after each job succeeds. Auditor is prepared/syntax checked, not executed. DO NOT dispatch another
same-branch control run before store starts: GitHub would replace that pending run.
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

Last connect11559 REAPED timeout12s at18:27UTC (also35727 at18:16); no install attempted.
Route is10.77.77.3 viawg0. At18:40UTC the peer handshake was about8hours old. Do not repeatedly
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
