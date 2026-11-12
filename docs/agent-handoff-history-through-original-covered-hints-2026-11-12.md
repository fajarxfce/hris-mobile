# Agent continuation — communications long run

Updated 12 November 2026, 14:07 UTC. Continue the PRD and push every commit. All 54 acceptance groups OPEN.
No subagents. One compiler OR emulator. Phone install-only: announce and select serial; never
launch/drive UI, reset data or uninstall. Continue beyond checkpoints.

## Accepted: bounded account metadata discovery

Mobile workspace `.work/space-history-locator-scan-2026-11-12` accepts four source/test files
(two data), baseline f11eea10. Manifest0741f7720eca8ca6b2ae6ff1fd9303bc1501b848f06eea95401b47ed50c406b5,
5,133 inputs. Account reader observes max17 raw checkpoints, consumes max16, skips internal/root/
browse metadata and returns at most requested meaningful locators. Cursor preserves unreturned
rows. Other readers, one-history-per-turn service, schema and worker bound are unchanged.

Tests-only red1/15312 REAPED47/8 in66.543s, all8 intended failures audited; format1/80540 and
format2/83510 REAPED35.774/35.821s. Focused1/53587 REAPED113/0 in263.917s, strict audit exit0:
47 desktop +19 Android +47 browser, exact prior IDs except one mapper rename across3 targets.
Proof1/37780 REAPED21/0 in133.208s and strict audit pass. Immutable proof APK152,426,868B/
d0b076feecf5b29543eb36ae218bd26de1c3320ac6e3faf60b07f8ba5244d7c9, SDK8 x86 library6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95.
Full1/49554 REAPED PASSED482.295s; audit62848 REAPED exit0:
14,555 =4,784 fresh +21 source-exact proof +9,750 unchanged dependency-verified. Zero failures/errors/
skips. Report manifest48441b26346a4b9de21e3042e09ba295bcb89c6bd37217a2ee79981420fb5296.
Full check, Android host/release/lint, Wasm, iOS metadata, proof, format/architecture/detekt pass.
[Compact evidence](measurements/collaboration-account-history-metadata-scan-2026-11-12.json). No compiler/emulator remains active.

## Next root: native covered-head hint requeues

Native worker6/36993 REAPED FAILED1 in125.280s, boot28.420s. It now reaches consumeScheduled:134
(the !request(covered head) assertion). Before that, first-page content, bounded completion within
unchanged4 turns, empty demand and Company EOF all pass. Same-boot read-only inspection: history
recovery complete; no physical/inventory work; Company15 refs/zero pending/one message; final hint
creates one Published Latest task with head and zero retry. Preserve fixture
96d6a8f7-4513-4aa7-9274-d046840c3f87 and original worker6 images. No active HTTP/storage errors,
source/APK changes, compiler overlap or phone interaction. Two pre-arm startup errors are not the
demonstrated cause. Final repeat equality and offline worker reopen remain UNREACHED.
Native18/reg1/bootstrap1, Matrix27/send4/sync5/secret2/provider2.

The queue use case checks Published Space history even for LEGACY bindings, but canonical facts
are correctly retained in Company/original-device history. Fix local coverage in the owning use
case; don't copy facts into Space, suppress unknown work or weaken the final no-op assertion.
Company coverage requires provider=null; Published Space uses non-null provider identity.

The eight-file coverage change is now APPLIED and uncommitted in Mobile. Workspace
`.work/space-original-covered-hint-2026-11-12`, baseline accepted metadata6d9f745a/14,555 cases.
Current5,136-input manifest6dc8053f533b4c818afe39d2a17ddeb3db75481aa3fb27ef7f1004a19124879a.
It adds named pure Original coverage policies; the owning queue use case receives an explicit
parent, resolves registered legacy Company/device provenance, reads one source/pending page,
confirms unchanged progress, and fences registration/admission. Unknown/unclean history stays
on normal Latest alias routing. DI and the native caller pass the existing dependencies/owner.
No content duplication, native opening, schema, worker limit or retry changes.

The existing Published fixture now explicitly uses SPACE origin with no Original references;
its original borrowed LEGACY binding obscured the bug. A separate Original fixture uses distinct
Company/conversation/device identities. Thirteen new Original regressions cover exact clean facts,
unknown/unfinished/active pending history, different registration, session/denial/replacement,
storage/cancellation and preserved independent work. Existing21 Published tests keep their IDs.

Format1/96904 REAPED35.586s. Focused1/26911 REAPED FAILED117.278s before tests: the active-pending fixture used Int0 for an opaque String cursor. Production compiled; zero tests ran. Exact8-file source is archived as failed-focused-candidate-1. Only the fixture changed to valid cursor "seek".
Focused2/54233 REAPED PASSED70/0 in332.067s (34 domain desktop +34 browser +2 shared DI).
Strict audit inline exit0; all prior case IDs preserved. Report manifest
06099ea61e29a97c35e4957e811b67780aa7b41789293bd6371fd26f36a74d02.
Proof1/45999 REAPED PASSED21/0 in158.391s; strict audit inline exit0.
APK152,433,182B/b780610908ed1edd8f33c8242b76a9f72f98c9364439ebccc93ffb54524b9200,
immutable proof1/accepted-proof.apk; source/library unchanged, SDK8 library unchanged.
Native worker7/13438 REAPED FAILED1 in129.265s (boot31.503): frontend protocol PASSED,
including bounded4 turns, first-page content, Original journal, no-op repeat/covered hint,
cleanup and offline new-process reopening with unchanged revision/no runtime/database released.
The failure is the old Group turns2 expectation (actual4), not a frontend failure.
Trace native18/reg1/bootstrap1, Matrix30/send4/sync5/secret2/provider2; zero active HTTP failures.
Fixture08f895c0-163d-44a8-89b7-7d12f7125dc4 preserved. The diagnostic runner then tried to inspect
an already-cleaned proof package; its JSONDecodeError is secondary, not an app storage error.
Runner now skips failed-state inspection when the frontend completed successfully.

Group has three uncommitted test-only files with fixed request categories/finite phase snapshots;
existing counter maps and assertions are unchanged. Prior source1 evidence preserved. Format2/
33897 REAPED22.797s; source2/77053 REAPED79.234s, audit exit0. Source8,745 manifest
d629d53ee8f0852069233a3741e8ee9ff9a3d7c2f31cd5ea00027a9ecee976b2; test class
fcde1a38c34ca5334af51970d4c29d3dfe6512fdb2bda5df044dfe05e7db7199.
Native worker8/9702 REAPED FAILED the unchanged Group turns2 assertion in119.244s,
boot30.450s; frontend again PASSED all flags. Fixturea7a83cf4-3602-4b20-a663-55c76b4d97f6.
Phase trace: physical staging21 Matrix/no native; subsequent9 Matrix (2 sync,4 history,
2 account-data,1 SDK GET). Native18 comprises11 device GET,1 identity GET,4 other GET,
1 registration and1 bootstrap; no transport acquisition or active HTTP failure. Final phase
matches offline-complete phase. Exact fixture/trace is retained; do not erase failed images.

Group old constants were updated after that audit; no Mobile bound or assertion was removed.
Format3/71701 REAPED21.001s, source3/81343 REAPED77.772s, strict audit exit0. Group source
manifest56d3997415f214dbfe86d5462d198c0132a3451f5ea34da198e4c2b0be98a3de; test class
df7cd71be098b03b9fd7c35d27eb402692d4d2ee9f5816968f60df35778ce747.
Full native suite1/42409 REAPED PASSED7/0 in296.282s, boot29.399s; strict accept-suite.py
inline exit0. Six prior case IDs preserved plus the registered worker. Source/APK unchanged,
no compiler overlap, fresh isolated2560MiB/two-core emulator stopped, phone untouched.
[Native measurement](measurements/collaboration-original-covered-hints-native-2026-11-12.json).

Mobile full1/69373 REAPED FAILED351.352s:7,061 fresh cases/35 failures. All35 belong to
legacy-routing domain tests (ResolveCollaborationSpaceIncomingAliasTest21,
ResolveCollaborationSpaceIncomingBindingTest4, ResolveCollaborationSpaceOriginalPhysicalBindingTest10).
They share SpaceIncomingRequestFixture through SpaceIncomingPhysicalResolutionFixture. Changing its
default to SPACE silently changed the legacy fixtures. No production failure was inferred from this.
Exact failed eight-file candidate and source map are retained under failed-full-candidate-1.

Correction changes ONLY two commonTest files: restore the prior LEGACY default; explicitly select
SPACE in all21 Published queue tests. Thirteen Original tests already select LEGACY. All other
fixture consumers retain their original semantics, and no assertion/case ID changed. Production,
DI, native fixture and accepted APK bytes remain unchanged. Current manifest6dc8053f533b4c818afe39d2a17ddeb3db75481aa3fb27ef7f1004a19124879a.
Format2/49947 REAPED PASSED35.673s. Focused3/44681 REAPED PASSED140/0 in186.468s:
104 domain desktop +34 browser +2 shared DI. The private browser filter still selected only
the two coverage classes; no browser failure was observed. Browser-routing1/40854 REAPED
PASSED70/0 in28.953s for the three additional routing classes. Combined strict audit inline
exit0 accepts210 unique cases,11 XML reports, no dropped/renamed IDs, same source/library.
Report manifest1a60517c76e4e350d4bcdde191a99714df736be65f3332dbaa572a2d87fbff57.

Full2/session34926 ACTIVE; no source edits or emulator during build. After completion run
accept-full.py full-2. Full2 explicitly reruns Shared desktop, Data desktop and proof-unit
tests in addition to existing required tasks; no failed-full1 reports are reused. Expected14,581
local. The seven native cases need no duplicate run for the two commonTest-only corrections;
native-test-only-source-delta.json verifies the precise difference, and full2 must confirm the
same APK hash. Historical native manifest remains0c3be4a8 rather than claiming execution on
the later whole-tree manifest. Production/native inputs are unchanged. Current full auditor
requires that identical APK and marks native acceptance only after verifying the two test paths.

Group source/test/native proof commit e6a3bc36 is pushed;19367 REAPED. Documentation165c8d45 is pushed;20047 REAPED. Mobile source is uncommitted
pending successful full2. Prepare compact full measurement, commit/push, then dispatch the prepared
exact-source CI once (tree clean/local acceptance/pushed commit required). Expected17,673=12,559
portable(6,006 desktop/952 Android/5,601 browser)+5,114 Apple; zero case renames. Continue beyond CI.

Next work: preserve the seven cases and add a separately scoped native real-key-during-held-write
proof, followed by a finite quiet-period request observation. Current receipt proof covers initial
reconciliation, one lost write reply and two foreground resumes; it does not prove a newly received
key during the held transaction. SDK8 Room.discardRoomKey can create a new fictional peer session;
extra real events legitimately change the timeline, so do not weaken existing four-event/head/
first-page assertions to accommodate it. Own all resources and keep phone/Bima stores untouched.
Detailed pending contract is .work/space-original-covered-hint-2026-11-12/next-native-key-contract.md.
Native worker6 remains the preserved behavioral failure; compile failures are not red proof.

## CI, prior commit and phone

Coalescing source f11eea10 / Group b620663c is committed/pushed and accepted14,538 locally.
Documentation4257dfac /18cdb196 pushes51703/31022 REAPED. Hosted control43220169006 on exactf11eea10
is fully accepted17,611 (12,516 portable+5,095 Apple). Portable audit14182 REAPED, artifact13946618859/4,041,558B; ZIP0b5ddb30ca10349d0510b23b41bdbad3dd11b5ca15a4eb9667d9a4a972f7c99a. See compact collaboration-space-incoming-discovery-ci-2026-11-12.json; source/case/archive/log hashes verified. State/auditor live in
`.work/space-incoming-discovery-coalescing-2026-11-12/hosted-43220169006`. Expected17,611=12,516
portable (5,987/947/5,582) +5,095 Apple. Audit against fully accepted progressive43200304791.
Metadata6d9f745a / Groupa867612a pushed;26418/15641 REAPED. Hosted metadata43224352728 has portable accepted12,533 and Apple active on exact6d9f745a; audit83236 REAPED, artifact13949271713/4,034,807B, ZIPa10230d40b80c33bc14c55ca820e3ed6bad1a2927798e9f43792369d50b783c0. Dispatch81388 REAPED, single attempt. Expected17,634=12,533 portable(5,993/952/5,588)+5,101 Apple. State/auditor live under metadata workspace hosted-43224352728 and use coalescing43220169006 as predecessor. Coalescing Apple audit87382 REAPED; artifact13949887539/805,739B, ZIPa9674c6897ccc2131c4c5a4623e9b2d1980b37186fb31b3eb56bf90016d56877, all2 reviewed Apple renames and prior case IDs verified. Do not poll or redispatch43220169006. Metadata Apple is building the iOS host. Apple job timeout90 minutes, host step30; portable job60. Apple host success does not prove native SDK8 execution.

Release89 is INSTALLED on10.77.77.3:5555, verified09:10UTC (65584 REAPED). Do not reinstall.
SDK6, install-only upgrade, identity preserved and no UI interaction. Current SDK8 x86 proof APKs
are not phone releases. No availability or Firebase IAM question pending.

## Protected environment

Starter badfc0d is clean/accepted906; these HRIS policies are not template ports. Preserve Inspector
gradlew.bat and protected SDK source/wrapper/consumer worktrees. Do not rebuild/push forks or
dispatch legacy SDK4 workflow. Live90163c32500ff2fa005013eaf83b026d4bdcf5ff/schema116.4, readiness ON/
Group OFF, migrations117–140 undeployed. Do not replace live services from Group worktree or print
Firebase credentials. Never resend recorded native messages; preserve Bima keys and all failed images.
[Prior detailed handoff](agent-handoff-history-through-history-metadata-scan-2026-11-12.md) preserves
all source/artifact/native evidence and earlier accepted CI, including exact protected paths.
