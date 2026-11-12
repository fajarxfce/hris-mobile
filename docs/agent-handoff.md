# Agent continuation — communications long run

Updated 12 November 2026, 16:37 UTC. Continue implementation beyond checkpoints and push every commit.
All54 PRD acceptance groups remain OPEN. No subagents. One compiler OR emulator. Phone install-only:
announce and select serial; never launch/drive UI, reset data or uninstall. ADB availability question is pending after a fresh connection timeout.

## Native late-key worker recovery fully validated

Workspace .work/space-late-key-recovery-native-2026-11-12 in Mobile and Group.
Five Mobile proof files and four Group test/driver files; no production change.
Source manifests: Mobile887a2915efd183103f4b3332f93f8493f34811f54583e93dee5ab3e9775a5e44 (5,138inputs),
Group9b8904cafd1425e9c7e7d146e36a49d30c47b7fdff9a9384de8d28cae1552d38 (8,750inputs).
Mobile format1/68873 and format2/17784 REAPED; proof1/66434 failed Detekt LongMethod before proof
compilation. Archived failed-static-candidate-1. Moved pure payload checks to existing evidence
owner; no assertions removed. Proof2/23805 REAPED PASS21/142.435s and accept-proof.py passed.
Immutable proof-2/accepted-proof.apk:152,433,161B/5ccb64752cf57a2d7696af20dc007a0fde7f822f3ac5a91e139d0a2766952935,
SDK8/x86native6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95.
Groupformat1/39382 REAPED22.101s; source1/16261 REAPED81.277s. Test classSHA
a04193063aabbd9648bf38d73f6948e7817455998ec26844fc1001c58f207aed. Python65/0,
protocol logSHA19935cedd920864e241bfe15df6c8b7950c60f4d5d6aff2dc9e8d01fe4db987e.
Single late1/43704 REAPED PASS158.299s/boot31.532; strict native audit passed.
Suite1/89939 failed port reservation BEFORE emulator/test startup. Follow-up at15:55:20UTC found
no5562/5563 sockets, device or owned heavy process; transient occupant was not captured. No source
change. Suite2/59462 REAPED PASS9/0 in445.280s, boot31.465; strict audit accepts all eight prior IDs
plus the new recovery case. Fresh2560MiB/two-core emulator stopped; no compiler overlap or phone.
Actual two-generation recovery, same receipt/journal, original/edit/tombstone plus second message,
no unresolved references, exact completed projection in a further offline process and zero offline
server traffic pass. New case total18 native/33 Matrix,5 encrypted sends,7sync,4secret,2provider pages.
Fixture scheduler invokes the actual registered task; automatic OS relaunch and new collector
concurrency remain separate. No new phone release is needed for these proof-only changes.

Mobile full1/61257 REAPED FAILED after1295.276s:14,339 fresh cases, one Mocha10-second
browser timeout in BrowserCollaborationIncomingHistoryLocatorStorageTest.
removingTheCursorRowDoesNotRestartTheRemainingPage. No data assertion failed. Preserved exact
source/report as failed-full-candidate-1. locator-browser-1 completed28/0 in37.265s with identical
case IDs/source/deadline; no active process remains from that run. Root cause is unconfirmed.
Full2/17129 REAPED PASS136.927s, full unfiltered tasks and original deadlines. No source/timeout
or assertion change. Auditor39114 REAPED PASS:14,581 =2,385 fresh +11,975 individually passing
source-current reports +221 unchanged dependency-verified. Failed full-1 XML is excluded. All prior
case identities retained, no replacement/removal. Report manifest
 d77fd0f2c2290ec66e1a66fb53b9efdcd1cb0d0e1cb3a5b4949186f3d12df5ef.
Full check, Android host/release compile/lint, Wasm, iOS metadata, proof and static gates pass;
proof APK, Mobile sources and Inspector inputs unchanged. Full evidence and contract now updated.
Nine-case native/proof/source gates remain accepted; do not rerun absent new changes or failures.
Phone90 remains accepted but uninstalled: connect13358 REAPED timeout12s, pending availability
question. Never reinstall89, launch/drive/reset/uninstall the user's phone.

Next: exact SDK8 Apple framework and native proof. Read-only local draft is at
.work/sdk8-apple-planning-2026-11-12; not yet tracked/built/dispatched. The current Swift proof pins
upstream26.10.27 and the app selects unavailable Apple Matrix runtime. Prepare a separate owned
checkout, same immutable nine SDK8 Rust patches plus narrowly hashed Swift build-tool patch:
replace explicit rustup stable calls with cargo build --locked under pinned RUSTUP_TOOLCHAIN1.96.0.
Build real macOS/iOS/device/simulator slices and verify exported methods, encrypted-store continuity
and listener disposal before production bridge adoption. Common-host CI is not native chat support.
Do not mutate/rebuild protected SDK forks or published Android assets. All54 PRD groups stay OPEN.

## Accepted: canonical Original covered hints

Mobile branch feat/chat-send-timing-20261107, workspace .work/space-original-covered-hint-2026-11-12.
Eight source/test files (3 production), baseline6d9f745a. Current5,136-input manifest
6dc8053f533b4c818afe39d2a17ddeb3db75481aa3fb27ef7f1004a19124879a.
The queue use case receives the explicit owner, resolves current registered LEGACY Company/device
coordinates, validates exact source/EOF/clean pending work and unchanged progress, and fences local
admission/registration. A covered hint makes no write/wake/schedule. Unknown or unfinished history
keeps normal Latest alias routing. Newer/independent demand remains. No Company facts are copied into
Space, and no native opening, request, schema, worker bound or retry timer is added by coverage.

Thirteen new Original tests complement21 Published tests. The shared fixture retains its original
LEGACY default; Published tests explicitly select SPACE. Full1/69373 exposed35 regressions when the
shared default was changed globally:21 alias,4 binding,10 original physical cases. Exact failed source
is retained as failed-full-candidate-1. Only two commonTest files corrected fixture selection; no
assertion or production/native input changed. Focused1's earlier failure was compile-only (Int used
for opaque String cursor), not a behavioral red. Native worker6 is the actual preserved covered-hint
failure. See the archived handoff for each earlier run and source hash.

Focused3/44681 REAPED PASSED140/0 in186.468s; browser-routing1/40854 REAPED PASSED70/0 in28.953s.
Combined strict audit accepts210 unique cases/11 reports, no renamed/dropped prior IDs, source/library
unchanged. Report manifest1a60517c76e4e350d4bcdde191a99714df736be65f3332dbaa572a2d87fbff57.
Full2/34926 REAPED PASSED594.429s; audit/evidence97646 REAPED exit0:
14,581 =12,543 fresh +2,038 unchanged dependency-verified; zero failure/error/skip, zero case renames.
No failed-full1 reports are reused. Report manifest
8c1c4b5ff90a3c70ae10d38f48f4ee98340e841604fbb40d92990aeb4d210b57.
Full check, Android host/release compilation/lint, Wasm, iOS metadata, proof, format/architecture/detekt
passed. [Compact full evidence](measurements/collaboration-original-covered-hints-2026-11-12.json).
No compiler/emulator is active.

## Accepted native suite and exact scope

Group source3:8,745 inputs, manifest56d3997415f214dbfe86d5462d198c0132a3451f5ea34da198e4c2b0be98a3de.
Source/test commit e6a3bc36 pushed (19367 REAPED); docs165c8d45/38273c8c pushed (20047/33258 REAPED).
Seven-case suite1/42409 REAPED PASSED296.282s, boot29.399s; strict audit exit0. All six previous
case IDs preserved. Fresh2560MiB/two-core emulator stopped; no compiler overlap or phone interaction.

Proof1/45999 PASSED21; immutable .work/space-original-covered-hint-2026-11-12/proof-1/accepted-proof.apk
152,433,182B/b780610908ed1edd8f33c8242b76a9f72f98c9364439ebccc93ffb54524b9200, SDK26.10.23-hris.8,
x86 library6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95.
Native whole-tree Mobile manifest was0c3be4a8ce87881e81680f3935a6564f6b59c1aa42a100753d3479262c415018;
the later difference is ONLY the two commonTest fixture files. Full2 verified the byte-identical APK
and unchanged production/native inputs; do not claim native reran on the later whole-tree manifest.

Worker finishes within the unchanged4-turn bound, exposes one Company message before provider EOF,
preserves Original journal, makes empty-repeat/covered-hint no-ops and reopens in a new offline
process with identical revision, no runtime opening and released database. Phase trace: physical
staging21 Matrix/no native; subsequent9 Matrix (2 sync,4 history,2 account-data,1 SDK GET). Final totals
Matrix30/send4/sync5/secret2/provider2, native18 (11 device GET,1 identity GET,4 other GET,1 registration,
1 bootstrap), no transport acquisition or active HTTP failures. Old Group turns2/constants were
updated only after preserved worker7/8 phase traces; no Mobile assertion or bound was removed.
[Native evidence](measurements/collaboration-original-covered-hints-native-2026-11-12.json).

## Accepted: real late keys and bounded realtime idle

Workspaces .work/space-late-key-native-2026-11-12 in Mobile and Group. Four Mobile proof files,
four Group test/driver/trace files; no product policy, retry timer, schema or SDK artifact changed.
The peer rotates a real key and sends a fifth encrypted event before adoption. One source sync
receives it while the first receipt transaction is committed and its reply held/lost. Two different
receipts commit/ACK once each, the first is not rewritten, the same native instance drains both,
and the unfinished first recovery retains the successor generation. Offline reopening reads the
identical obligations in a new process without SDK opening and releases storage.

The30-second window permits only the existing10-second cursor long polls, with bounded2–4 requests,
actual completed durations9–20s, start spacing>=9s and unchanged native/non-sync counters. This is
not a claim that a connected realtime app makes zero requests or that pending history was drained.
The app source owner/native/storage are actual; fixture activation and OS scheduling are replaced.

Mobile format2/52140 REAPED36.736s, proof1/42606 REAPED PASSED21/112.575s. Immutable proof-1/accepted-proof.apk
152,433,182B/a97b93375016117bd77860c8751358fe34bf0c1911945c1b17b8964488b3ae3a,
SDK26.10.23-hris.8/x86 library6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95.
5,137 inputs, manifestfa9e0e92bf2617c93e76d9641d938b72d04c461b7bf19c0915fdc7d2c0639ac9.
Group format3/90848 REAPED19.976s, source3/37794 REAPED79.995s,8,748 inputs manifest
daf48fe37ba6a1639b0ace11a0d89ab2693f0432f1dfd63a8b1f2acbf720e0ad;
late test class1652e61a06c3f1b967c128379aeddc744c2538989139bf1db254d288f3581b14.
Python protocol58 passes (10 new,29 event,19 original); shared CI discovery includes the new driver tests.

Single late3/46108 REAPED PASSED167.971s, boot30.471s; strict native audit passed. Full suite2/84165
REAPED PASSED8/0 in368.326s, boot30.459s; strict audit passed. All7 prior case IDs/assertions retained.
Fresh2560MiB/two-core emulator stopped, no compiler overlap or phone use. Late case total native7,
Matrix29/send5/sync7/secretStorage2, no active HTTP failures. Full-suite quiet window30,009ms.
Full Mobile1/77371 REAPED PASSED101.257s; audit/evidence49182 REAPED accepts14,581:
952 fresh +21 current-input proof +13,608 unchanged dependency-verified; zero fail/error/skip or
case replacement. Full check, Android host/release compile/lint, Wasm, iOS metadata, proof and static
gates pass, same APK/source/Inspector inputs. Reports00ec81f6042a2fa8a52a0b41586dc715471c50539811161335b21fd5d39a5732.

Failure provenance: late1/4724 stopped before receipts because the fixture expected send order while
provider IDs are sorted; all3 actual sender IDs/counts were correct. Sorted expected IDs, preserved
failed-staging-candidate-1. Late2/2528 proved receipt processing but rejected3 normal sync polls during
30,008ms; source confirmed the existing timeout10000 pump. Added bounded timing observation instead
of changing any product timer; preserved failed-quiet-candidate-2. Suite1/52411 failed its port
reservation before emulator/test start; later check found no owned process/listener/device. Suite2
ran unchanged sources after port availability. All failed userdata remain. Earlier two launcher
errors occurred before compilation (missing pre-format manifest and wrong cwd) and are in state.json.

## Release90 accepted; phone connection pending

Mobile65459d8cc5c7e307032d4f937853b18f37e1c3e5 and Groupe8e4a880 are committed/pushed.
Release workspace: /home/fajar/hris/hris-mobile/.work/release-phone-2026-11-12-build90.
Exact pushed source, accepted14,581 local and8 native cases, publishedSDK8 and unchanged Inspector
inputs bind this package. x86 build63455, verifier51374, startup72164, ARM build99033 all REAPED PASS.
Final verifier/acceptor56112 completed: release-acceptance.json accepted at15:32:22.808032UTC.
Actual product cold/reopened startup passed in a fresh2560MiB/two-core emulator, which stopped.
First onboarding screen visually inspected, no employee account or chat latency claim.
ARM124,317,071B/SHA44018233229d128f626eb8c4a429c38a7b530e477dda7cc4659265f696e3f26c;
version90/1.0.0, same signer, nondebuggable, zero lint,16KiB ELF/ZIP, SDK8 symbols verified.
Executable DEX matches accepted x86. [Release evidence](measurements/collaboration-release90-2026-11-12.json).

Phone10.77.77.3:5555 was absent, reconnect21786 timed out after12s at15:35UTC. Asked asynchronously
whether address changed or is active again; no installation90 has started. Release89 already installed,
do not reinstall89. On availability verify device state, announce EACH installation then run from Mobile:
setsid --wait python3 .work/release-phone-2026-11-12-build90/install.py --install-only
Inspect existing install-result.json first if present; don't blindly retry. Keep identity/data and verify
installed bytes. Never launch/drive/reset/uninstall phone. Mobile HEAD is no longer locked by packaging;
the accepted artifact remains bound to65459d8c. Keep live Group OFF/schema116.4.

Continue communications after installation; current native recovery and pending full check are recorded
at the top. Native AppleSDK8, account replacement and the remaining PRD still require implementation
and acceptance. Keep live Group OFF/schema116.4 until its separate rollout gate is satisfied.

## Hosted control fully accepted

43236203251 on exact Mobileab513ace is fully accepted:17,673 =12,559 portable +5,114 Apple,
zero case replacements/removals. Audits83958/75582 REAPED. Do not poll or redispatch.
Portable6006desktop/952Android/5601browser. iOS host and push-envelope steps passed; native Matrix
SDK8 Apple execution is separate. Later65459d8c adds Android proof/docs only to this common source.
[Compact evidence](measurements/collaboration-original-covered-hints-ci-2026-11-12.json) retains
artifact IDs, ZIP/report/log hashes and original failed-auditor correction. The inherited prior-stage
increment17 was wrong: declared totals/exact IDs already matched; actual26 portable/13Apple additions,
no removals. Preserve original auditor and audit-correction.json; no behavioral assertion was loosened.

## Other CI and phone

Coalescingf11eea10 CI43220169006 fully accepted17,611 (12,516 portable+5,095 Apple); audits14182/87382
REAPED. Do not poll/repeat. Compact collaboration-space-incoming-discovery-ci-2026-11-12.json contains
artifact/ZIP/report/log hashes and reviewed4 portable/2 Apple replacements.
Metadata6d9f745a CI43224352728 fully accepted17,634 (12,533 portable+5,101 Apple), audits83236/31245
REAPED. Compact account-history-metadata-scan-ci evidence includes all artifact/case/report hashes.
Do not poll or repeat either accepted CI run. Apple job90min,
host step30; portable60. Apple host/contract success does not prove native Matrix SDK8 execution.

Release89 was installed on10.77.77.3:5555 at09:10UTC,65584 REAPED. SDK6 install-only upgrade, identity
preserved and no UI interaction. Do not reinstall. SDK8 release90 is accepted but not installed yet.
Firebase IAM is verified; no credential question is pending; ADB availability remains pending.

## Protected environment

Starter badfc0d remains clean/accepted906; these HRIS policies are not template ports. Preserve
Inspector gradlew.bat, protected SDK source/wrapper/consumer trees, Bima keys and every failed native
image. Do not rebuild/push SDK forks or dispatch legacy SDK4 workflow. Live90163c32500ff2fa005013eaf83b026d4bdcf5ff,
schema116.4, readiness ON/Group OFF; migrations117–140 undeployed. Do not replace live services from
this Group worktree, replay recorded native messages or print Firebase credentials.
[Detailed prior state](agent-handoff-history-through-original-covered-hints-2026-11-12.md) retains
failure evidence and links to exact protected paths, historical source and native/CI artifacts.
