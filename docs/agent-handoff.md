# Agent continuation — communications long run

Updated 12 November 2026, 09:23 UTC. Continue the PRD and push every commit. All 54 groups OPEN.
No subagents. One compiler OR emulator. Follow AGENTS.md and clean layer boundaries.
Phone install-only; announce installations and never launch or drive the phone UI.

## Native result and phone update — 12 November 2026, 09:23 UTC

Release89 installed09:10:48.391148UTC in27.683s; session65584 REAPED. Explicit phone
10.77.77.3:5555, install-only upgrade. Device APK SHA-256 equals validated
4c790d2314f53c37bfdc3108ac4b801ab33aa9fc177904001fd16793fd0ef52d. Existing identity preserved;
no launch, UI driving, uninstall or data reset. This delivered APK is SDK6, NOT current SDK8 proof.

Native1/67430 REAPED FAILED after224.323s:7 cases/5 reported failures. Two old controls passed;
one old registered incoming worker failed with IncomingDeferred; the remaining4 cases DID NOT START
because the harness protected the failed proof package. Keep its userdata and private fixture
22b3bedb-d717-4c34-bf90-c95d48ed8385. Its two503 collaboration_recipients_changed responses occurred
BEFORE trace.arm and do not establish the worker failure cause. No provider acquisition followed.
Static investigation: old fixture omits process incoming collection while the current LEGACY alias
resolver requires an actually retained sync source. Runtime confirmation still pending; do not
weaken request budgets or blame startup503. All production inputs remain unchanged.

Fresh isolated diagnostic1/68248 REAPED PASS1 case146.658s, boot33.58s, same APK/source manifests.
This independently reaches the NEW process receipt case: actual native initial callback, encrypted
write with lost reply, durable receipt before native ACK,2 foreground resumes with1 receipt write
and1 ACK, process collector joined and exact receipt digest after offline process reopening.
Matrix32 requests (4 encrypted sends/3 sync/2 secret storage/2 provider pages); native13
(1 registration/1 bootstrap replay/4 direct transport). No compilation during emulator, phone untouched,
emulator stopped. This is a partial native result, not acceptance of the7-case suite. Independent
native publisher/audit remains to be implemented. Workspace state records both reaped sessions.

## Current: native process-source validation

Mobile source98fa082d and Group docs f84482a8 pushed41204/11026 REAPED. Application CI43186091942
dispatched ONCE08:49:17.822306UTC (97812 REAPED), expected17,552. Hosted state exists.

Workspace `.work/space-incoming-sources-native-2026-11-12` exists in BOTH Mobile and Group worktrees.
Preparer53746 REAPED applied4 Mobile fixture files and3 Group test/protocol files. NOT committed or
native-accepted. Mobile format80896 REAPED35.503s; proof82359 REAPED124.856s; audit60137 REAPED.
`:check`,21 fresh proof cases,format,architecture,detekt and APK pass;14,476 previous case IDs remain
verified unchanged.5,131 Mobile inputs/fea5595ef756b068b904acab92dcf5f97b1fd9dc4c655a9897c6810456a1a5ef.
APK152227098B/49ecb109dcf7c757b59346d7b0098d1377a332eddc4859461e754c062078f59f, SDK8x86_64
native library6e4e177e2e9ec01df4a4b238bbefaeba6b228fed0d139224f2b0424484e2cd95 independently verified.
Group format1/86152 failed before edits because runner used Mobile task :formatKotlin; command/log
preserved. Correct repository task :format passed format2/91822 REAPED20.96s. Source1/60033 REAPED
138.848s,8,745 inputs/9c7f3daf294f682182c1a3cda168069ca39e477b936754c867e1ba2b93d95f36,
format/architecture/testClasses pass; source audit inline exit0. Python27 cases pass (4 new).

Native1/67430 ACTIVE in Group workspace. One fresh2560MiB2core emulator, precompiled768MiB test
harness, no compiler overlap. Expected7 cases in2 XML reports:6 previous SDK8 controls plus new
application receipt/lifecycle/offline case. `native-config.json` names proof-1/source-1. Do not change
Mobile/Group/Inspector inputs while running. No native acceptance script/publisher yet; implement
independent evidence audit after the result. Preserve any failed native store and trace. Never resend
protected demo messages. Do not label this native-accepted before exact evidence succeeds.

The new fixture wraps repository boundaries only to hold then commit one real encrypted write and
return a lost-reply failure; native ACK checks the exact durable receipt independently. It invokes
real application source ownership under actual root/setup/native publication. Two foreground passes
must keep one receipt/write/ACK/instance. A separate process opens offline and checks matching receipt
digest and original messages. Old fixture variants preserve empty incoming override. New Python
strict evidence rejects incomplete/extra fields, wrong booleans/counts and changed offline digest.
Late keys DURING an app write, replacement accounts, long quiet request counts and Apple native key
adapter remain separate acceptance work even if this baseline passes.

Runtime CI43182776077 portable12,444 audited40813 REAPED (current job131075551755). Apple plugin
resolution failure log reviewed; ONE failed-job retry sent08:53:42.697912UTC,51634 REAPED, now job
131075548574. Do not send another retry blindly. Application CI43186091942 Apple131073503970 failed
before tests because Maven Central returned429 for coroutines-test1.10.2, disabling stdlib resolution;
logSHA39653ea69cd660129384f36dfbffc8bcc87e7e1a1f9cf5085ab049bc896471d8. Review saved, no retry yet;
portable remains pending. Later one failed-job retry after the run completes is justified; avoid
adding dependency requests while throttled. No dependency version/verification policy changed.

Phone92283 REAPED08:57:38.769500UTC,20.020773s timeout; next>=09:31:18.790273UTC. No install.

## Process-owned incoming sources accepted locally

Workspace `.work/space-application-sources-2026-11-12`, baseline
`cef427683f8167436faa7d8bec3f523ae7a16453`. 13 files/5,130 inputs, manifest
`2cb91e7d99aa490f59289e984f94158b01e9bd40c6ba649a72664165575113db`.
Format69985 REAPED/36.452s; focused27621 REAPED303/203.674s; browser8337 REAPED192/92.412s.
495 focused accepted. Full1/56719 REAPED420.609s, build successful. Initial audit41002 rejected
318 presentation cases restored from cache because its conservative closure includes the changed
domain test file. Original run/audit failure preserved. Only those318 cases were rerun in
cached-revalidation-1/29416 REAPED41.091s. Audit29171 REAPED verifies separate source-matched
archive provenance, prior IDs and dependency closures.14,497 local=12,459 fresh+2,038 unchanged.
No Kotlin/test failure or production correction in this stage. Read measurement for exact hashes.
Publisher EXECUTED. Commit/push accepted source/evidence, then dispatch-hosted.py ONCE.
Hosted plan remains UNDISPATCHED, expected17,552=12,475 portable+5,077 Apple.

27 new app cases (22 source owner,3 lifetime,2 foreground) plus2 common late-metadata cases.
ApplicationCollaborationLifetime owns one process collector. Exact native publication selects key
and sync children; root/account/instance changes cancel AND join the previous children. Failures
are independent; foreground entry starts one finite pass without a retry timer. No new SDK/client,
sync acquisition, native close handle or ViewModel dependency. Category-only diagnostics deduplicate
repeated sync failures. CI explicitly selects the new app class. Canonical metadata precedes primary
conversion, and late physical hints survive completed history sweeps; unresolved primary work gets
confirmed fresh recovery demand. SDK8 already emits initial reconciliation and import notifications.
Source wiring is enabled; no live deployment or native-runtime acceptance is claimed.

## Next: actual native callback through the application owner

`native-draft` contains4 Mobile files and `native-group-draft` contains3 Group files, all UNAPPLIED,
UNREVIEWED and Kotlin UNCOMPILED. Private Python protocol27 cases passed0.003s (4 new).
Read next-native-state.json/design.md. Partial draft generators already ran; do not rerun them.
The existing HrisSpaceRecoveryProofHost replaces ApplicationCollaborationLifetime and omits the
incoming delegate. The draft adds an optional delegate for the new fixture while preserving all
old variants. New host uses actual application owner, native callbacks and encrypted repository;
one write is held, then committed while its response is lost. ACK independently checks the exact
durable receipt. Two foreground resumes must keep one write/ACK/native instance. Separate offline
process reopening compares the receipt digest and unchanged messages without SDK acquisition.

Review DI resolution order, race/teardown paths, bounded write gate and strict Group/Python proof.
No native preparer yet. Compile proof/server fixtures with no emulator, then run isolated fictional
fixtures on2560MiB2core emulator with the precompiled768MiB harness. No phone UI, live backend
mutation, protected message resend or SDK rebuild. This draft does NOT yet prove late keys during
an app write, changed accounts, long quiet request bounds or Apple native callbacks. Those and the
remaining PRD work stay open. Protocol fakes/Apple compilation are not native SDK acceptance.

## Hosted evidence

Runtime publication cef42768 / Group cf66781b pushed81544/54467 REAPED.
CI43182776077 was dispatched ONCE08:25:10.618006UTC (11348 REAPED), expected17,519.
Apple job131062567285 failed BEFORE compilation/tests: Gradle could not resolve Kotlin MPP2.3.21
in the pinned inspector included build. Log SHA93dfa9a1a736f49c02f0403bb5ef9f1c49ff981d6f8a8b610f36b2954737fc30.
Workflow/catalog/verification metadata match accepted consumer66f537a4; underlying download cause
is not logged. Failure review saved. Portable is pending. Do not blindly redispatch; after the run
finishes, one retry of the failed job is justified by unchanged inputs and adjacent successful run.
Consumer43176939497 FULL17,470 audited: portable78231 / Apple57691 REAPED.
Targeted43172811544 FULL17,314 audited: portable57960 / Apple2467 REAPED.
Binding43168158361 FULL17,170 and lifecycle43165090113 FULL17,085 audited. Source-specific audit
files remain authoritative; a green workflow alone is not acceptance.

Phone36696 REAPED08:23:43.714411UTC,20.020610s timeout; next>=08:57:23.735021UTC.
No install. Validated release89 remains ready. No repeated availability question.
[Previous checkpoint](agent-handoff-history-through-runtime-publication-2026-11-12.md).

## Protected environment and prior evidence

Validated release89 is in .work/release-phone-2026-11-10-build89. It is SDK6, nondebuggable arm64,
123809167B/4c790d2314f53c37bfdc3108ac4b801ab33aa9fc177904001fd16793fd0ef52d. Use install.py
--install-only. No phone UI/reset/uninstall. Main is SDK8, with protected source/wrapper worktrees;
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
