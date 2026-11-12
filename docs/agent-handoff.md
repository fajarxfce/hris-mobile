# Agent continuation — communications long run

Updated 12 November 2026, 13:19 UTC. Continue the PRD and push every commit. All 54 acceptance groups OPEN.
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
Current5,136-input manifest0c3be4a8ce87881e81680f3935a6564f6b59c1aa42a100753d3479262c415018.
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

Format1/96904 REAPED35.586s. Focused1/26911 REAPED FAILED117.278s before tests: the active-pending fixture used Int0 for an opaque String cursor. Production compiled; zero tests ran. Archived exact8-file source as failed-focused-candidate-1 before correcting only the fixture to valid cursor "seek". No production change. Focused2/session54233 ACTIVE, expected70 cases (34 domain desktop +34 browser +2 shared DI). No test acceptance yet. Do not edit source/start emulator. After completion run accept-focused.py focused-2; accept-full.py now uses focused2/proof1, not the failed run.
Then exact proof APK/21 tests, native worker7 with unchanged assertions, and full baseline gate
(expected14,581 local =14,555+26). All runners/audits are prepared privately.
Native worker6 remains the behavioral regression; constructor/compiler failures are not red proof.
Group exact turns2/Matrix31/native14 assertions remain unreached and need actual final trace review.

## CI, prior commit and phone

Coalescing source f11eea10 / Group b620663c is committed/pushed and accepted14,538 locally.
Documentation4257dfac /18cdb196 pushes51703/31022 REAPED. Hosted control43220169006 on exactf11eea10
remains active; last query17879 REAPED showed portable and Apple in progress. State/auditor live in
`.work/space-incoming-discovery-coalescing-2026-11-12/hosted-43220169006`. Expected17,611=12,516
portable (5,987/947/5,582) +5,095 Apple. Audit against fully accepted progressive43200304791.
Metadata6d9f745a / Groupa867612a pushed;26418/15641 REAPED. Hosted metadata43224352728 is active on exact6d9f745a; dispatch81388 REAPED, single attempt. Expected17,634=12,533 portable(5,993/952/5,588)+5,101 Apple. State/auditor live under metadata workspace hosted-43224352728 and use coalescing43220169006 as predecessor. Coalescing12762 REAPED last status: portable application-bindings/compilation and Apple setup-adapter steps still running.

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
