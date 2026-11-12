# Agent continuation — communications long run

Updated 12 November 2026, 13:06 UTC. Continue the PRD and push every commit. All 54 acceptance groups OPEN.
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

Private `.work/space-original-covered-hint-2026-11-12` has source-review.md and TWO DRAFT domain
files under draft/: a named pure original-coverage policy and QueueCollaborationSpaceIncomingRoom.
They are NOT applied, formatted, compiled or tested. draft-base-files.json anchors existing source.
Draft adds explicit owner/native-family admission, registered legacy Company/device matching,
finite source/pending/repeated progress reads and registration fencing. Needs review, DI/caller
updates and meaningful regression tests before application. Avoid assertions based on pagination
token or matching IDs alone; preserve unknown/unclean work on normal alias routing.

Important test finding: SpaceIncomingRequestFixture borrows a LEGACY binding from
OriginalTransportFixture even for tests labelled Published Space. Make its default an actual
SPACE room (origin SPACE, both original refs null) and introduce explicit Original cases. Use
different published/original device and conversation IDs. OriginalQueueMatchFixture's old native
family must not be silently reused as the current family when constructing registered coverage.
The native failure is already the real regression; don't claim compile-only failure as red proof.
Group exact turns2/Matrix31/native14 expectations remain unreached and await complete trace review.

## CI, prior commit and phone

Coalescing source f11eea10 / Group b620663c is committed/pushed and accepted14,538 locally.
Documentation66193342 /3a70bfb9 pushes19803/58065 REAPED. Hosted control43220169006 on exactf11eea10
remains active; last query17879 REAPED showed portable and Apple in progress. State/auditor live in
`.work/space-incoming-discovery-coalescing-2026-11-12/hosted-43220169006`. Expected17,611=12,516
portable (5,987/947/5,582) +5,095 Apple. Audit against fully accepted progressive43200304791.
Metadata expected hosted17,634=12,533 portable(5,993/952/5,588)+5,101 Apple; not dispatched yet.

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
