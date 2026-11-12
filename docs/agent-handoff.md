# Agent continuation — communications long run

Updated 12 November 2026, 18:26 UTC. Continue implementation beyond checkpoints and push every commit.
All54 PRD acceptance groups remain OPEN. No subagents. One compiler OR emulator. Phone install-only:
announce and select serial; never launch/drive UI, reset data or uninstall. Latest phone connect35727 REAPED timeout12s at18:16UTC; release91 remains uninstalled.

## Native account-replacement proof in progress; shared Apple registry accepted

Current uncommitted work is .work/native-account-replacement-2026-11-12 in Mobile and Group.
Mobile adds a seeded SessionRepository and actual application-source fixture: first key receipt
commits while only its reply is held, actual account index/session changes to B, old runtime must
retire, B cannot read A receipt, late callback must not ACK, then A receipt/private identity restore.
Existing encrypted original/edit/tombstone checks and another offline process remain mandatory.
No production policy or login/server-revocation behavior is changed or claimed. Group has a separate
NativeAccountReplacementTest and strict driver; old nine native cases remain unchanged.
Mobile format1/41505 and format2/19999 REAPED PASS. Proof1/39875 REAPED failed static LongMethod:
reopened had101 lines against100; exact failed source retained. Scenario evidence selection moved
to existing HrisEventReadEvidence, no assertions removed. Proof2/10657 REAPED PASS21 in114.780s,
accepted immutable152,433,161-byte APK76b4ba13de5f3dcb40303e8ce8822dfcae6fad6062bd1609dc8163645b019f6c,
SDK8 native unchanged. Mobile7 proof files/5,151inputs/70bd7258a94872ca0d7167eb691e1635fdde47facf480da41d9d1fc7515e4093.
Group3 files/8,753inputs/0bb0b9a7f9a082063bf97fe570fce53e2df3f51c144d492f523b1eac0bb1585d;
70 driver tests pass. Format1/68660 REAPED PASS20.522s. Source1/23811 still requires reaping;
then accept-source.py source-1, native.py replacement-1, exact native audit, native.py suite-1
(10 cases), full Mobile :check and accept-full.py. Native acceptance is NOT yet claimed.
No emulator runs until compilation ends. Preserve failed input/data, one compiler OR emulator.

Shared registry43266580439 is fully accepted: portable12,604 + Apple5,135 =17,739 cases,
no dropped/replaced test IDs. Apple auditor3626 REAPED; artifact13971608237/811,082B/
c203f86b9476516c6f33c9beb5ea1360592a394b95699793a8284d46f772ce5d. Native iOS tests and
host build passed. Compact measurement now records both audited jobs; do not repeat them.
Inventory43273697021 has started (portable131394617779; Apple131394617992 queued).
Store dispatch89306 REAPED created43279731370 on exact836ac52575daf1747aac1cf94a6768a80998023f;
PENDING behind inventory, no cancellation. Expected12,604portable +5,156Apple, exact13+8 methods.
Its hosted-audit.py portable|apple hosted-43279731370 is prepared/syntax checked, not executed.
DO NOT dispatch another same-branch control run until store starts: preserve the pending proof.
Native SDK8 Intel43267867675 remains building separately; no four-case Swift acceptance yet.
Release91 accepted/uninstalled; connect35727 timeout at18:16. No install attempted. Continue work.

## Apple SDK store acquisition locally accepted; preserve pending inventory CI

Mobile .work/apple-sdk-store-2026-11-12 adds AppleCollaborationSdkStoreSource and8 Apple tests,
extending the existing native directory owner; only3 Apple source/test paths differ from inventory.
Source5,149 inputs/16e2108e8e6d7abd0949cd3a57ccf342bdcf45ea5ce299500d1bbf007156de1a.
Caller provisions a private backup-excluded root. Only acquire creates scoped directories/owner.lock,
with0700/0600 requests. Existing acquisition requires original DB pair/cache/lock and never repairs.
Both modes reject redirected DB/WAL/SHM entries. SDK clients/credentials remain untouched; bounded
entry inspection, the shared native lock, cancellation and idempotent cleanup retain ownership.
Format1/86139 REAPED PASS35.550s. Validate1/12083 REAPED PASS123.948s: format/architecture/detekt,
actual ARM64 and iOS metadata compile, compileTestKotlinIosSimulatorArm64 (8new +13unchanged
inventory methods), full :check. Zero new tests execute on Linux. Strict audit retains exact14,626
unaffected platform report hashes; Inspector sources and accepted proof APK remain identical.
No native Apple OS execution, product binding or new phone APK is claimed. Git contract and compact
measurement are collaboration-apple-sdk-store.md and measurements/collaboration-apple-sdk-store-2026-11-12.json.
No compiler/emulator is active. Push this implementation and record its exact commit for later CI.
DO NOT dispatch another same-branch control run while inventory43273697021 is pending: GitHub would
replace that pending proof. Once inventory starts, queue the store commit with expected12,604portable
and5,156Apple (prior5,135 +13inventory +8store), retain exact21 Apple method IDs and prior report IDs.
Shared registry43266580439 Apple remains in progress; portable is already accepted. Native SDK8
Intel43267867675 remains building separately. Do not conflate these with the four Swift native cases.
Phone connect1855 REAPED timeout12s at17:59UTC. Release91 accepted/uninstalled; no new install attempt.
The existing availability/IP question is pending. Continue work, push every commit, live Group OFF.

## Apple existing inventory locally compiled; native acceptance is next

Mobile .work/apple-sdk-inventory-2026-11-12 adds four Apple-only source/test files:
AppleCollaborationSdkInventorySource, its explicit AppleSdkDirectoryLock resource,13 native
inventory cases and their filesystem fixture. Existing-only descriptor-anchored acquisition,
no-follow descendants, nonblocking flock, idempotent cleanup and protected-read cancellation.
Shared repository/use-case policy is reused; no product binding or SDK client is enabled.
Contract: Mobile docs/collaboration-apple-sdk-inventory.md. Source5,147 inputs/
128f41be62ae5117f475ef24980859fd254c12cbe99ab45a7cb1190186010d21. Only these four Apple files
differ from accepted registry inputs; other platforms and proof APK are byte-identical.
Format1/16591 REAPED PASS36.253s. Metadata1/3320 REAPED FAILED76.262s solely because native
Dispatchers.IO requires importing kotlinx.coroutines.IO. Exact candidate archived; added that
import without changing ownership or assertions. Metadata2/53930 REAPED PASS79.794s, actual
iOS ARM64 and iOS metadata compile, format/architecture/detekt pass. Test-compile1/30110 REAPED
PASS73.142s, actual compileTestKotlinIosSimulatorArm64 including all13 new test methods.
Local-check1/64494 REAPED PASS119.539s; full :check executes0 new tests, reusing14,626 exact
accepted unchanged-platform report digests. Strict local audit confirms the four Apple-only
changes, identical source during all gates and identical Inspector inputs/proof APK. No compiler
or emulator remains active. Compact evidence is in both repositories' measurements directory.

Mobile dd405660a3ea920a4f27781f006d83b7b7fd636a is pushed (45023 REAPED). Dispatch48333 REAPED;
CI43273697021 is PENDING on that exact SHA behind43266580439, without cancellation.
https://github.com/fajarxfce/hris-mobile/actions/runs/43273697021
Expect12,604 portable and5,148 Apple cases (prior5,135 plus13 new inventory methods), retaining
all prior identities. Use .work/apple-sdk-inventory-2026-11-12/hosted-audit.py portable|apple
hosted-43273697021 only after each job succeeds; exact13 Apple method identities are captured.
Native Apple execution is NOT yet claimed. Shared registry
CI43266580439 Apple job131351645845 remains in progress; its portable audit is already accepted.
The new workflow must queue behind it without cancellation. Intel native SDK8 CI43267867675 is
still building; on success use Mobile .work/sdk8-apple-planning-2026-11-12/audit-hosted.py to verify
its exact four Swift contracts, package/proof digests, source manifest and five architectures across three native slices.
That read-only auditor is syntax checked but has NOT run. No SDK publication/product activation.
Release91 remains accepted and uninstalled after connect59791 timeout. The availability question
is pending; announce and use --install-only after connection. Never drive the phone UI.

## Release91 accepted; installation waits for phone connection

Mobile .work/release-phone-2026-11-12-build91 is fully accepted from frozen/pushed
source b816431824e4255fcc101641bc6257894ff6649e; source manifest
 d42bae7604ca4b3d37fad253ab660c806bf2dc4e51e31d03089b9dfa6edba7c4 (5,143inputs).
x86 build44236 REAPED PASS638.089s; verification84440 REAPED PASS. Startup83877 REAPED
PASS93.611s on fresh2560MiB/2core emulator: boot31.499s, cold2891ms/reopen1557ms, separate
processes each stable10s without fatal exception. Actual onboarding screenshot inspected; emulator
stopped before ARM compilation. No authenticated employee or phone UI was used.
ARM build87006 REAPED PASS44.425s; verification7779 REAPED PASS; final acceptance at17:37UTC.
ARM APK124,317,071B/45e26962b2acc839498558affd2fbce059f602f29d1b62e25f2b55276740cf7d.
Both APKs have identical executable DEX8fc42ca34377f35529d808e451b97e275e98f17490f12c14e03f465653619aef,
nondebuggable release, prior dev signer, SDK26.10.23-hris.8,16KiB ELF/ZIP checks and zero lint issues.
Accepted inputs:14,626 local cases and9 native cases. See measurements/collaboration-release91-2026-11-12.json.
Mobile source lock is now released. Release91 supersedes uninstalled90;89 was installed earlier,
never reinstall89. Phone connect59791 REAPED timeout12s at17:37UTC; existing availability/IP question
is still pending. No installation was attempted. Once connected, announce EACH actual installation,
then run build91/install.py --install-only with its explicit10.77.77.3:5555 serial. Never launch,
drive, reset or uninstall the user's phone. Preserve retained app identity and compare exact APK bytes.

Shared CI43266580439 portable job131351646411 SUCCEEDED on exactddf65dbe485aff6f2ef74d22469a723892d04d93.
Auditor10948 REAPED PASS12,604 =6,027desktop+955Android+5,622browser; all old test IDs retained,
zero replacements. Artifact13967932208/4,036,781B, digest8464806ef675082eaf50c80f6dba2ea90ad9a41c096dd240b1998d7399510a59;
report manifestce9fe1e49d58396910d6d3314e5f2ed5e52f8c3a3b236f9331843f7fbec55d74.
Apple shared job131351645845 and Intel native SDK8 CI43267867675 remain IN_PROGRESS. Do not repeat
portable audit/gates. The native SDK job passed its guards and is building; no Swift native result yet.
Continue Apple adapter work and audit those exact results when complete; no production Apple runtime
or live Group activation. All54 PRD groups remain OPEN.

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

## SDK8 Apple native candidate prepared; hosted result pending

Mobile adds tool/build_collaboration_apple_sdk.py, ten negative/positive artifact guard tests,
a separate SDK8 proof manifest and three Swift tests, a hashed Apple manifest/build-tool patch,
and .github/workflows/collaboration-apple-sdk.yml. Original upstream Swift proof is unchanged;
the driver stages its raw-key source/test plus the three SDK8 tests into one four-case package.
Same immutable nine Rust patches and Android SDK8 manifest5f93684eac1c1e26f95f250d87ec34b91bdd22ad34a2a8af5659f8a210623b3f.
Additional Swift builder patch9f05d91ba44e45fe0f51ac9fdba7f09d7b3a865ae648c0f577fd2578bd45a45e
changes explicit stable cargo calls to cargo build --locked under pinned Rust1.96.0.
Five native architectures, sequential builds/two jobs, iOS16/macOS12. Verifies each slice's actual
architectures and exact SDK8/recovery symbols. Original raw-key continuity plus SDK8 passphrase
identity/wrong-key refusal, absent-store no-create, cursor and listener/observer ownership tests
must all pass before unsigned iOS device AND simulator linking. No production runtime binding.

Local10 tests pass; YAML/Python syntax valid; dry-run Apple patch applies and every protected SDK8
source digest remains unchanged. Native Swift/Mach-O execution is NOT claimed. Candidate inputs
and local evidence are in Mobile .work/sdk8-apple-planning-2026-11-12; see its Git contract at
docs/collaboration-sdk8-apple-candidate.md. Mobile dde4dfd1a3a85b8374599d9b346374fd6a25648e was pushed (8901 REAPED).
Hosted SDK8 candidate run43261761056 attempt1/job131334639711 FAILED before any step: GitHub
reported hosted runner acquisition failure and macOS ARM capacity constraints. Exact annotations
are retained in .work/sdk8-apple-planning-2026-11-12/failed-hosted-start.json. No source/build failure.
Same-commit rerun20007 REAPED; attempt2/job131345691423 also FAILED before any step with the
same acquisition/capacity annotations (read16155/57501 REAPED). No Swift compile/runtime failure.
Both failure records are preserved. Official runner-images README confirms macos-15-intel.
Mobile9b7c4d3f changes ONLY that runner and its documentation; SDK source/patches/five targets/four
Swift tests unchanged. Push65823 REAPED. Intel SDK8 run43267867675 is IN_PROGRESS on exact head
9b7c4d3fac0127e2f440e8dc5fb420dc66c22cfc; unlike the ARM attempts, it acquired a runner.
https://github.com/fajarxfce/hris-mobile/actions/runs/43267867675
https://github.com/fajarxfce/hris-mobile/actions/runs/43261761056
Group handoff779e1780 pushed (34071 REAPED). Audit the exact native artifact before acceptance. No package publication. Preserve
failed diagnostics if it fails; change only demonstrated causes. One local Gradle gate currently owns the build; see the next work paragraph.
The actual Apple app still selects an unavailable Matrix runtime; bridge implementation follows
accepted native SDK8. Do not rebuild/mutate protected SDK forks or Android release assets.
Phone90 remains accepted and pending connection; connect26825 REAPED timeout12s. Server wg0 has
its route/peer, but the last phone handshake was over6hours old at16:45UTC. Existing availability
question remains pending; do not retry installation until explicit ADB connection succeeds.

## Portable runtime registry locally and natively accepted; hosted validation running

Workspace .work/sdk-runtime-registry-portable-2026-11-12 in Mobile and Group. SDK-free registry and
Space identity moved to commonMain. The only registry code transformation replaces its Any/synchronized
monitor with SdkRuntimeMonitor/locked (including lambda labels); whitespace-normalized comparison proves
all other logic unchanged. JVM uses the same synchronized monitor, Apple NSRecursiveLock, and Wasm its
single non-suspending event loop. Reentrancy is required for inspection-to-reservation calls. Limits5
clients/2borrowers and policy/SDK/transport unchanged. Two18-case test files moved byte-for-byte;
existing Android threaded tests remain. Three new common cases exercise reentrancy/exception cleanup
and competing capacity/inspection. This is HRIS-specific, not a starter-template port.

format1/96964 REAPED PASS35.626s. Current5,143-input source manifest
 d42bae7604ca4b3d37fad253ab660c806bf2dc4e51e31d03089b9dfa6edba7c4,13 changed source paths.
registry-jvm1/42828 REAPED PASS976/187.837s:955 Android host +21 desktop. Strict audit retains ALL952
old Android case IDs. registry-browser1/55060 REAPED PASS21/96.900s, exact common case identities.
Proof1/85489 REAPED PASS21/133.424s; immutable proof-1/accepted-proof.apk
152,433,161B/dd2568e9aa6a55b7da571383ef31c1f1d055bd63b30c77f849e5e8e5152e16a0.
SDK8/x86 library6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95.
Group reuses byte-identical precompiled prior source1 (8750 inputs/9b8904cafd1425e9c7e7d146e36a49d30c47b7fdff9a9384de8d28cae1552d38).
Native suite1/78343 REAPED PASS9/426.365s, boot28.417. Strict native audit passed with the SAME nine
case IDs and assertions. Fresh2560MiB/2core emulator stopped; no compiler overlap or phone interaction.

Full1/2779 REAPED PASS470.690s. Auditor85674 REAPED accepts14,626:
4,829 fresh +21 current-input proof +9,776 unchanged dependency-verified; zero failure/error/skip
or case replacement. All prior14,581 identities remain; the exact21 registry cases also pass on
Android/desktop/browser. Report manifest45a43d45b883b1a85916bf72ac21c677aa01ab4fc52effc38093ecacf8826d54.
Full check, Android host/release compile/lint, Wasm, iOS metadata, proof and static checks pass with
the same accepted proof APK and input manifests. Compact measurement is now fully updated.
No local compiler/emulator active. Do not repeat these gates absent source changes or failures.

Mobile production commitddf65dbe485aff6f2ef74d22469a723892d04d93 pushed (33626 REAPED); Group docs
398aa634 pushed (88477 REAPED). Shared CI43266580439 is on that EXACT head; dispatch72623 REAPED.
Apple job131351645845 and portable131351646411 both running at last check. This uses the same
collaboration-control.yml as accepted baseline43236203251/ab513ace (its historical CI branch explains
why a branch-filtered run listing does not include it). Expected12,604 portable =6,027 desktop +955
Android +5,622 browser; Apple5,135; total17,739. Auditor is ready in the Mobile workspace:
python3 .work/sdk-runtime-registry-portable-2026-11-12/hosted-audit.py portable hosted-43266580439
python3 .work/sdk-runtime-registry-portable-2026-11-12/hosted-audit.py apple hosted-43266580439
Only run each after its job completes; preserve exact prior IDs/artifact digest/CRC/step evidence.
The shared Apple monitor tests are independent of the four Swift SDK8 candidate tests. No production
Apple runtime is enabled. Next Apple boundaries are documented in local apple-next-boundaries.md:
existing-only inventory, exclusive directory leases and synchronous identity-read cancellation;
reuse shared repositories/use cases instead of copying Android orchestration. Native acceptance
precedes product binding. Phone90 remains accepted/uninstalled; all54 PRD groups OPEN, live Group OFF.

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
