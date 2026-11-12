# Agent continuation — communications long run

Updated 12 November 2026, 10:37 UTC. Continue the PRD and push every commit. All54 groups OPEN.
No subagents. One compiler OR emulator. Phone install-only: announce, explicit serial, never launch
or drive the phone UI, reset app data or uninstall. No final checkpoint response; continue the work.

## Current: progressive Company incoming content

Progressive content is committed and pushed: Mobile197b55d9 / Groupc192ddcc.
Push sessions96985/4493 REAPED. Mobile now has five uncommitted native fixture files; Group has
three corresponding native protocol/test files. No production change after197b55d9.

Workspace `.work/space-original-progressive-content-2026-11-12` (Mobile). Exact baseline is the
accepted native receipt fixture source now committed as7c3fca00;5,131 source inputs. Current16-file
manifest767b1500d339e2e8e2ef08500b9e6458ae9040098ae17193a3af1d20f0e6a539.
Eight production files allow exact Provider/Pending captures to read bounded Company content after
a provider page commits. Source/device/Company/room/runtime checks remain unchanged. Reconciliation
precedes unresolved event reads; confirmation precedes yielding the next provider page. Provider
work cannot complete its queue or demote for missing keys before EOF. No schema/SDK/UI changes.
Eight test/fixture files include10 new cases (5 app/5 common), plus4 intentional behavior test renames
(7 target-specific XML IDs). test-case-id-replacements.json is explicit; all other predecessor IDs
must survive. Baseline14,497, final expected14,512 local. Candidate/base file hashes and archives
are retained. Do not rerun the preparation scripts: later corrections are already applied.

Format1/64525 REAPED36.458s. Focused1/61744 REAPED FAILED189/1 after145.432s: physical snapshot
validator still restricted Pending. Archived failed-focused-candidate-1 BEFORE correction. Added
same content-phase predicate there and exercised wrong-owner matrix under Provider and Pending.
Format2/65816 REAPED37.132s;15-file manifest1ddbb8b3b44dd479c6920ea6d7bd290b04691548d5550494188ea30007b0e12f.
Focused2/45019 REAPED244/276.408s and browser1/97635 REAPED189/107.927s passed; inline audit accepted
433 cases with explicit ID replacements. Full1/25843 REAPED FAILED2393/1 after301.669s: one registered
worker test still expected NO content before EOF. All its no-backoff/source/continuation assertions
passed. Archived failed-full-candidate-1; corrected only that expectation to exact unresolved content,
confirmed non-exhausted progress and no early completion/demotion. Production unchanged after433 focus.
Do not rerun unchanged focused browser work merely for this test-only correction.
Format3/3683 REAPED35.425s, no formatter edits, current16-file manifest above.

Full2/34210 REAPED PASSED in750.173s. Audit77429 REAPED accepted14,512:11,007 current full2,
1,467 archived passing Data cases from full1 with identical dependencies, and2,038 unchanged baseline.
The first audit87274 conservatively rejected Data XML hashes; its failure and corrected provenance
remain archived. The only full1-to-full2 source delta is the unrelated app worker test expectation.
ProofAPK149059016B/6080a8fe4f8c7c338f1a155c7b13797aedf23ea293ab0a74d91b1e7a6c87d2a7;
SDK8x86 native hash6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95.
See [measurement](measurements/collaboration-space-original-progressive-content-2026-11-12.json).
Hosted CI43200304791 dispatched once from197b55d98c30e19347e15e7012fc5737014e639c at10:25:52.726669UTC;
session77811 REAPED. Expected17,572 =12,490 portable (5974/947/5569) +5082 Apple. Source-specific
plan/state/audit files are in hosted-43200304791. Baseline accepted runtime43182776077; explicit
reviewed case replacements are required. Not audited yet; do not dispatch again.

## Next: actual canonical worker native acceptance

Active workspace `.work/space-original-worker-native-2026-11-12` in Mobile and Group. Drafts were
reviewed and applied: five Mobile fixture files, three Group protocol/test files. All production
inputs match accepted197b55d9. Actual process collector, retained registration-based adoption,
SynchronizeCollaborationSpaceDevice and durable-source observation replace the old incomplete
fixture setup. Worker and separate offline reopen read Company history with explicit physical
coordinates, both metadata indexes and no duplicate Published content. Strict flags companyStorage
and actualSourceRetained are required. Existing four-turn bound, first-page content, empty-repeat,
covered-hint and old exact backend request assertions remain; native acceptance is NOT complete.

Mobile format1/1860 REAPED36.924s. Proof1/55870 REAPED FAILED54.665s, zero tests: Detekt found
reopened scenario109 lines. Failed candidate archived before correction. Receipt comparison moved
to existing pure evidence assertions; repeated offline runtime coordinates captured once, with both
before/after checks preserved. No production change. Format2/21982 REAPED35.044s; proof2/88058
REAPED PASSED124.739s/21fresh tests. Audit73555 REAPED accepted14,512 (21fresh+14,491 unchanged).
Current5-file/5131-input manifest910a7e78dd0559680aff42a6cd516a79625d7964d353d493dbff9c3c00bc0d1a.
ProofAPK149932375B/61c634866f194e0bbb24cb62bfc8ca507a4bd59dcd7df6f83db832a61994fb0a,
immutable copy proof-2/accepted-proof.apk. Not a phone release.
Group Python29cases passed0.006s. Format1/36796 REAPED21.202s with no edits. Group3-file/8745-input
manifest674bcfed931027c2ed49d35e338fe66071b03ae00cf98220ee2142085b424fe9.
Group source1/46483 ACTIVE: compile.py source-1 compiles server tests and checks format/architecture.
Do not edit Group source until reaped. Then accept-source.py source-1 and native.py worker-1 for
one isolated registered-worker diagnostic. Native config points Mobile proof-2 / Group source-1.
One compiler OR emulator; emulator2560MiB/2cores, explicit emulator-5562, preserve failed userdata.
Initial prep assertion rejected private Python bytecode before Group writes; corrected by selecting
only actual draft source files. Preparation did not drop any source or test.

Potential follow-up, source inspection ONLY: QueueCollaborationSpaceIncomingRoom currently checks
coverage through Space history; canonical worker stores Company history. Final covered-hint check
may expose this mismatch. Observe the actual failed native stage first; never insert duplicate Space
facts or relax the no-work assertion. See covered-hint-review.md in the active Mobile workspace.
Prior detailed design remains in .work/space-incoming-sources-native-2026-11-12/worker-repair-design.md.

## Accepted native receipt/source proof

Mobile7c3fca00 / Group1c36fb8b are test/evidence-only commits. Measurement
[collaboration-space-incoming-sources-native-2026-11-12.json](measurements/collaboration-space-incoming-sources-native-2026-11-12.json)
accepts six controls, explicitly NOT the complete worker suite. Native controls1/38538 REAPED6/0
in274.319s, boot28.447s, fresh2560MiB2core emulator, no compiler overlap/phone interaction; audit inline
exit0. New case: actual initial native key receipt, real encrypted write with lost reply, ACK only
after independent durable confirmation,2 foreground resumes/1 write/1 ACK/one instance, joined
collector and identical receipt after offline process reopening. Matrix32/native13 requests.
Proof1/82359 REAPED21 fresh unit cases,14,476 predecessors unchanged; audit60137. APK152227098B/
49ecb109dcf7c757b59346d7b0098d1377a332eddc4859461e754c062078f59f, SDK8x86_64 library
6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95. Source manifestfea5595ef756b068b904acab92dcf5f97b1fd9dc4c655a9897c6810456a1a5ef.
Group source1/60033 REAPED138.848s/8,745 inputs/9c7f3daf294f682182c1a3cda168069ca39e477b936754c867e1ba2b93d95f36;
Python27 original protocol cases passed. Group formatter is :format, Mobile :formatKotlin.

Initial native1/67430 failed7/5:2 passed,1 actual old-worker failure,4 not started because the failed
package was protected. Keep native1 userdata and fixture22b3bedb-d717-4c34-bf90-c95d48ed8385.
Diagnostic1/68248 independently passed the new receipt case146.658s. Worker-cause1/4024 REAPED114.267s
confirmed SDK source exists but exact demand lacks retained source; sole Published task is LEGACY;
actual resolver returns WaitingForOriginalSource. Fixture32d19e42-2137-40b7-bb53-339b77d1767d retained.
The old fixture omitted process incoming collection. Startup503s preceded trace.arm and were NOT
the demonstrated cause. Temporary diagnostic code restored. Its proof2/proof3 compile mistakes are
archived; proof4/83631 passed21/113.203s. Never deliver that diagnostic APK to the phone.

## Hosted and phone status

Runtime publication cef42768: CI43182776077 FULL17,519 accepted (12,444 portable +5,075 Apple).
Portable40813 and Apple74560 REAPED. One failed-job retry completed; do not retry or poll again.
Apple artifact13933157085/803728B/d1518594c2d29536de8a3487161d900cfb712af942a8ff0e25b1c3dfce4da871.
[CI measurement](measurements/collaboration-space-runtime-publication-ci-2026-11-12.json) preserves
report/log hashes. This is common Kotlin simulator validation, not native Matrix SDK8 Apple proof.
Application incoming sources98fa082d: local14,497 accepted (12,459 fresh+2,038 unchanged),13 files/
5,130 inputs/2cb91e7d99aa490f59289e984f94158b01e9bd40c6ba649a72664165575113db.
CI43186091942 portable12,475 audited1434 REAPED; one Apple retry95997 submitted09:20:02.265532UTC
after proven Maven Central429. Apple131088394619 still running, portable reattached131088396166.
Expected17,552. Do not submit another retry. Source-specific .work audit records remain authoritative.
Consumer43176939497 FULL17,470; targeted43172811544 FULL17,314; binding43168158361 FULL17,170;
lifecycle43165090113 FULL17,085 audited. Historical gaps remain in the linked history.

Release89 INSTALLED on10.77.77.3:5555 at09:10:48.391148UTC/27.683s, session65584 REAPED.
Install-only upgrade; device SHA matches validated4c790d2314f53c37bfdc3108ac4b801ab33aa9fc177904001fd16793fd0ef52d;
existing identity preserved. No launch, UI driving, uninstall or data reset. Delivered89 is SDK6,
not current SDK8 proof. Do not rerun the successful installer. No phone availability question pending.
[Previous checkpoint](agent-handoff-history-through-native-receipt-proof-2026-11-12.md).

## Protected environment and prior evidence

Validated release89 is in .work/release-phone-2026-11-10-build89. It is SDK6, nondebuggable arm64,
123809167B/4c790d2314f53c37bfdc3108ac4b801ab33aa9fc177904001fd16793fd0ef52d. Already installed; future phone updates remain install-only. No phone UI/reset/uninstall. Main is SDK8, with protected source/wrapper worktrees;
do not rebuild or push those forks, copy old runtime fixtures, or dispatch the legacy SDK4 workflow.
Never resend the recorded Control77/Warm77/Recovery78/Registry82/Parent83/History84/Selected86/Joined87
native messages; Draft66 was never sent. Preserve Bima stores and original keys.

Starter feat/network-inspector-20261107 at badfc0d is clean and accepted906fresh tests; HRIS policy
is not a template port. Inspector's unrelated gradlew.bat change remains protected. Live backend is
90163c32500ff2fa005013eaf83b026d4bdcf5ff/schema116.4, readiness ON/Group OFF; Group migrations117–140
remain undeployed. Do not replace live services from the Group documentation worktree. Firebase
Crashlytics IAM was already verified; never print the home service-account credential.

Full source-specific manifests, exact artifact/report/log hashes, historical CI gaps, SDK hashes,
protected worktrees and earlier native evidence remain in the linked history and measurements.
[Earlier checkpoint history](agent-handoff-history-through-room-key-binding-2026-11-12.md).
