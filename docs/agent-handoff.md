# Agent continuation — HRIS Mobile

Updated 9 November 2026, 13:05 UTC. This file describes current work.
checkpoints are preserved in [Git at b1630963](https://github.com/fajarxfce/hris-mobile/blob/b1630963/docs/agent-handoff.md).
Do not restart jobs or reapply candidates from that history without checking current state.

## Authorization and working rules

- The user authorized the entire chat, voice/video and meeting plan, long-running
  implementation, an atomic commit and push for each accepted change, and validated
  phone updates. All 54 communications acceptance groups remain open.
- Mobile proves reusable foundations before Starter receives them. Keep HRIS policy
  here. Follow AGENTS.md: domain policy/use-case orchestration, repository mapping,
  datasource I/O, screen state in ViewModels; explicit coroutine/native ownership.
- Notify before installing to `10.77.77.3:5555`. Use the explicit serial and
  `--install-only`; never launch or automate the user's phone UI. Use the owned emulator.
- Run one heavy local build OR emulator. The emulator uses 2560 MiB and two cores.
  Reap owned jobs before another heavy job. Do not kill the live API/worker JVMs.
  Preserve credentials, SDK stores, original messages and unrelated worktree changes.
- No physical iPhone interaction is required, but source/Apple runtime and push/call
  acceptance remain separate. Never describe Android-only proof as Apple acceptance.

## Source and repository state

| Checkout | Branch/source | Current state |
| --- | --- | --- |
| Mobile original | feat/chat-send-timing-20261107 / 0f6c843f | Encrypted binding storage accepted/pushed; owning preparation92982 under test |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 / 21101489 | Hosted verify42611795210/artifact13672363171 audited PASS;951cdbcf pushed |
| Inspector original | feat/matrix-rust-bindings-20261107 / f8ca3d6 | Verified public SDK6 integrated/pushed |
| Inspector isolated | feat/sdk6-distribution-20261109 / a6b8236 | Pushed; SDK6 distribution accepted |
| Starter | feat/network-inspector-20261107 / ffd0ac5 | Pushed; pinned public inspector workflows independently accepted |
| Platform original | feat/communications / 4d4df31f | Pushed; runtime unchanged |
| Platform group worktree | feat/communication-space / bf6e176a | Exact original-reference backend accepted/pushed; not deployed |

The isolated directories are under `/home/fajar/hris/.worktrees/`:
`mobile-inspector-ci-2026-11-09`, `inspector-sdk6-2026-11-09`, and
`communication-space`. Preserve the inspector worktree's unrelated gradlew.bat delta.

Runtime stays e558a6868399058a2dcdee97acfd0e7fa7f1ef07, schema116.4, readiness control ON.
V117–138 and organization routing are not deployed. An older live-result.json records
control OFF at deployment; the later accepted toggle is the current state.
Crashlytics access is verified; do not ask the user to grant IAM again.

## Accepted runtime fix and exact APK

2293ff05 fixes two publication/selection orderings that prematurely released an
available selected SDK client when its final background borrow closed. The registry
promotes only an available client for the fresh admitted foreground owner. Stale
retention, failed publication, revocation and background-only cleanup stay bounded.
No authority, retry, SDK-opening or synchronization policy is added.

[Source evidence](measurements/collaboration-selected-runtime-publication-2026-11-09.json):
two corrected RED cases fail on the old code; 31 registry cases pass after the fix.
Focused gate passes620 fresh cases (599 Android,21 app); full gate passes693 fresh
with current host reports reused, not added to the focused count. Zero test failures,
errors or skips; root checks, Android compile/lint0, Wasm/iOS source and quality pass.
All4529 app and82 library inputs remained fixed. Source tests use version85; packaging
and actual device proof exercise86. f9cdc708 adds closed activation diagnostics with
privacy, cancellation and no-self-retry tests; it does not change retry behavior.

APK86: `.work/release-phone-2026-11-09-build86/hris-mobile-dev-release-1.0.0-86-arm64.apk`.
SHA256 `4c53315ff2f1f29bc67f572d6f63527622200ad0c6b638ea65746386e03afc73`,
123465103 bytes, nondebug devRelease, unchanged development signer, exact SDK6 native,
16KiB alignment, Firebase/API and compiled backup exclusions verified; lint0.
Initial packaging94519 exhausted its2GiB heap after475.303s; preserved in attempt-1.
Retry7551 with3GiB/one worker passes88.189s; verifiers47872/8311 pass. All reaped.
Phone86 installed at10:25 UTC; exact installed APK digest and existing identity verified.
No launch, UI automation, uninstall or data clearing. [Accepted proof](measurements/collaboration-release86-accepted-2026-11-09.json).

## Native86 accepted and installed; prior failures preserved

Current `.work/selected-runtime-auth-trace-native-2026-11-09` completed all four120s
Home/room/resumed/cold observations:110 complete seconds each, active sync, no setup
requests or rejected admissions in those intervals. Paired refresh200×3 uses returned
credentials; otherAccess0 in both subsequent samples. Parent24→25 renews before expiry.
Six original Sent messages and history exhaustion remain after route/resume/cold.
One new Selected86 102110 (SHA933c96193d5c13b7d7590c159390c2a72ad0596aa3b82b0d64e0668ed9bc1cc8)
is sent once,2983.114ms tap→server, peer process stopped; server now SEVEN originals.
All four jobs38867/18238/25926/91581 completed; three remaining handles reaped after
context restoration, phases completion verified from durable record. Strict traffic,
independent review and release acceptance all PASS; no exemptions were used.365 total
request starts across startup/phase work,0 bootstrap/403. Setup during startup remains
a separate cost; one send sample is not p95. Phone installation53784 reaped PASS25.499s.

That86 store remains preserved as a prior baseline. Latest COMPLETE STOPPED store is
now `.work/joined-room-entry-native-2026-11-09/sender` at87, with EIGHT originals.
Preserve all four userdata/encryption files and the full AVD. Never resend any of the
eight originals or send Draft66 092041. Bima74 remains preserved. Root/Inspector
source freeze is released; the sole local compiler is now Mobile full89590.

The first native86 upgrade retained identity before a turn interruption. Its resumed
follow-up had one unclassified otherAccess observation and a stale preflight expecting
five events rather than six, stopping BEFORE Send. Both failures remain preserved in
[the prior observation](measurements/collaboration-release86-retained-observation-2026-11-09.json).
The new passive trace does not reproduce that access mismatch; it does NOT explain it.
Native84's Home failure also remains preserved. No gate was weakened or retried in place.

## Published dependencies and current CI

SDK `26.10.23-hris.6` is [published](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.6)
in immutable release438835525. AAR SHA256
a4aa1e2cb3136c0531bb35f7f06df10582c84c1974ce4e5fda7099d0ab533bb3;
Ivy SHA25669ea030963275ce080991e658172934441b73558e9de4d8e7ae3976b2f18558a.
APK stripped ARM64 SHA2564e15c92398521f5f5cd9fbb30cf327019faf32abe5302b64d8b4bdc07d75e9d4.
Do not rebuild or overwrite release assets. APK86 used the verified local mirror while
its original inspector still had the SDK5 catalog. The original inspector now f8ca3d6
integrates the verified public SDK6 source; current Room gates use that public catalog.

Inspector source742a2d1: CI42591111996 passes226 portable/Android host and69 Apple
simulator cases; downloaded artifact digests and XML verified. Apple Matrix native
binding and capture overhead remain open. Mobile consumer jobs pin that exact source
in all nine jobs across five workflows in the isolated CI worktree. First consumer
Apple CI42594287486 passed1,777 cases and Xcode app build; Windows also passed.
Web42599865437 at6c6aa4ca now passes the optimized production distribution in23m35.39s.
Its two artifacts, all26 web file hashes and95 memory samples are verified. Earlier
runner shutdown causes remain unclassified; launcher RSS is not compiler/Binaryen RSS.

Full verify failures were traced to strict SBOM checksums (eight official artifacts
verified), a cancellation test observing a separately owned coroutine too early,
and inconsistent notification navigation fixture data. A prior desktop SIGABRT remains
unclassified; bounded native backtraces are retained. Fifth verify42604884828 at3657ada9
failed2 of5,969 artifact XML cases. Its digest is verified. The test initially seeded
three conversations but published one and raced synchronization; the first wait-only
correction was insufficient. A document denial test clicked while Refresh was disabled
because mock HTTP completion preceded the owning acquisition's completion.

The first db9287a9 fixture correction failed locally:66 cases/1 failure during
initialization because only one incoming room is prepared. Sixth hosted42609635524
was cancelled on that evidence. The accepted21101489 correction restores the original
fixture and asserts its exact published one-room list, message preview and selected
Chat tab. Original typed link/resume/pending-clear/no-send assertions remain. The
document test waits for enabled Refresh after acquisition. No production timeout or
behavior change. Focused69722 PASS97.855s/66 fresh cases,0 failures/errors/skips,4,528
fixed inputs and quality checks. Commit/push8185 is reaped. Seventh verify42611795210
at21101489c2d371bf04dd00bd3648f8acec0e96d2/job129141795434 is running.
[Isolated evidence](https://github.com/fajarxfce/hris-mobile/blob/21101489/docs/measurements/pinned-inspector-ci-fixture-consistency-2026-11-09.json).
Do not merge or claim the hosted graph complete before artifact/XML review. These
hosted runs do not contain root Room dispatch/joined-room source. No application
distribution workflow was dispatched. Starter receives only the proven generic port.

## Current Room dispatch

The current increment adds one bounded original Room attempt through existing domain
repositories, exact durable uncertainty before HTTP, normalized mark readback,
cancellation-independent receipt capture and retained global denial. No replacement,
poll loop, SDK delivery or product route is introduced. Preparation and resolution
remain separate accepted operations. [Contract and evidence](collaboration-space-room-dispatch.md).

All jobs are reaped: focused34481 passes76 fresh cases in263.919s; full30025 passes5,963
fresh cases (5,364 desktop,599 Android host) in902.068s, zero failures/errors/skips and
release lint0; actual Chromium97424 passes74 cases in115.038s, including18 replay and11
actual IndexedDB storage cases. Root check, Android release compile, Wasm/iOS source,
format/architecture/Detekt pass. All4,532 app inputs and inspector inputs stayed fixed.
These new cases have not yet executed on Apple. The Room increment is pushed as4e7be081.

## Joined-room acquisition and accepted APK87

The current source moves raw joined-room acquisition into its datasource. SDK-local
Joined rooms retain their returned handle; other states close their lookup before
one existing join. Cancellation/failed membership/disposal/late rejection preserve
resource ownership. Current HRIS admission, queues, keys and transaction IDs are
unchanged. [Contract and exact evidence](collaboration-joined-room-entry.md).

RED36143 retained the previous unconditional join and failed its one expected case;
this is an equivalent extracted operation, not an unmodified application checkout.
Focused41563 passes611 actual Android host cases in71.209s, including10 raw adapter
and2 actual parent/child tests. Full3347 passes5,975 fresh cases (5,364 desktop,611 host)
in1,290.936s; zero failures/errors/skips/lint. Root check, Android release compilation,
Wasm/iOS source and quality pass. All4,535 app and inspector inputs stay fixed; all
jobs reaped. Focused uses build86 parameters, full uses87. No native speedup claimed yet.

APK87 build25852 PASS110.858s fromd8383438; static verifier97255 and exactSDK21568
pass. Nondebug devRelease, same development signer,16KiB alignment, Firebase/API and
compiled backup exclusions verified; lint0. SHA256
43373c6c4e6335149138630e7c2c9d6daa05edc1f76729ff01dae1023916ce8b,123481487 bytes.

Retained-native87 PASS670.401s through actual86→87 in-place upgrade, Home/room/resumed/
cold observations and one new Joined87 115837. Seven previous originals remain Sent
without Send/Retry gestures; history exhaustion survives reentry. Four120s phases
provide110 complete quiet seconds each with active sync, no setup traffic/rejected
admissions. Paired refresh200×3 keeps returned credentials; otherAccess0, parent26→27
renews before expiry. No room_join across the full run or new send;311 total classified
starts include startup/phase work. Original strict gate passes with NO exemptions.
Native emulator8719/phases46256/counter28538/paired78071 all completed and reaped.

One new original reached the server in2,066.684ms (prior86 sample2,983.114ms), recipient
process stopped. Room acquisition1ms versus509ms; timeline entry154ms versus704ms.
Setup still520ms and delivery825ms. Other local timings also vary; these are individual
emulator samples, not physical-phone latency or p95. Final screenshot confirms the
Sent bubble above the empty composer/keyboard; it is not first-frame evidence.
New original SHA f29bcfcabe98d1c6182ad817aecdd652d1b18f2b0c8b4d38e234561daca655e7.

[Exact release evidence](measurements/collaboration-release87-accepted-2026-11-09.json).
Phone install33957 is reaped PASS210.86s; exact installed APK digest and existing
app ID/first-install identity match. No launch/UI automation, uninstall or data clear. The copied installer had an86 version assertion;
review corrected it to87 BEFORE any installation, retaining all signature/native/
acceptance checks. Root app/Inspector freeze is released. Full89590 is reaped PASS; no owned compiler or
emulator is running. Do not restart87 or send again.

## Next implementation work

1. Phone87 and native87 are accepted; preserve all eight originals. Remaining send latency includes
   setup/acquisition and local command recovery. Measure the next owning change before
   claiming further improvement; startup cost remains separate.
2. Finish public consumer CI, preserve first failures and fix actual causes. After the hosted graph
   succeeds, integrate the verified inspector/CI source into the original checkout.
   Port the generic pinned workflow setup/checksums to Starter after Mobile proof.
   HRIS selected-runtime registry policy does not belong in Starter.
3. Continue organization chat after the accepted original Room dispatch: durable
   published binding/projection and exact LEGACY metadata validation. Group backend8d5724e2
   is accepted/pushed:266 fresh cases pass513.312s, zero failures/errors/skips,8,637 fixed
   inputs, native admission/actual LEGACY worker/HTTP/OpenAPI/format/architecture pass.
   Shared DI is unchanged; no full worker rerun, migration or rollout.
   Mobile has a seven-file DTO/domain-policy/transport-fixture increment. Focused16289
   is reaped PASS225.769s:49 fresh cases (23 preparation,13 Desktop transport,13 Android
   host transport), no failures/errors/skips,4,535 fixed app inputs/library and quality
   checks. One repeated non-null test assertion was then replaced with an asserted
   expected value; pre-full-cleanup.json records this only intervening source change.
   Full89590 is reaped PASS536.843s:4,162 fresh cases (3,549 desktop,613 Android host),
   zero failures/errors/skips/lint; root/Android/Wasm/iOS source and quality pass.
   All4,535 app/library inputs stayed fixed;445 preexisting suites are separate.
   No compiler/emulator remains. Do not repeat the accepted gate.
   [Current evidence](measurements/collaboration-space-published-room-reference-2026-11-09.json).
   The subsequent encrypted binding storage is accepted below. Its owning acquisition
   follows, then original SDK delivery/history, workers and product routing. Existing Prepare and
   Resolve room use cases are accepted; do not recreate them. The group backend is in
   the separate worktree; original Platform does not contain V117–138.
4. Continue rich message actions/media/stickers/voice, encrypted Drive/iCloud recovery,
   native MatrixRTC call lifecycle, LiveKit meetings and the remaining PRD. The source
   of scope is Platform docs/collaboration/prd.md and delivery.json. No group is complete.

Keep this current file concise and replace stale status instead of appending another
full history. Commit measurements with implementation; historical details stay in Git.

Starter's four-file pinned inspector/checksum port is accepted and pushed asffd0ac5.
Full88319 is REAPED PASS188.590s: root/Android/Web/iOS source, strict SBOM and quality;
62 fresh actual browser cases, no failures/errors/skips,1,052 app/49 library inputs
fixed. Lint0errors/45 existing advisories. Three APKs,366-component SBOM and26 web
files are hashed. All85 library files exactly match742a2d1; its fresh checkout's
reported gradlew.bat delta is a text/eol normalization artifact, with identical bytes.
No hosted Starter workflow or distribution was dispatched. No Starter compiler remains.

## Public inspector consumer integration accepted

The exact eight workflow/checksum/fixture paths from951cdbcf are now in Root after
confirming no intervening edits since common base2293ff05. Their pinned inspector
source is742a2d1; the transport cancellation fix was already identical in Root.
Consumer CI42611795210 passed7,950 cases, zero failures/errors/skips/lint. Artifact
13672363171 was independently hashed and audited, including three APKs and the
393-component SBOM. [Evidence](measurements/pinned-inspector-consumer-ci-2026-11-09.json).
Earlier failures are retained. This CI source predates Root's newer room increments.
Focused Root integration87699 is reaped PASS100.523s:66 fresh cases, zero failures/
errors/skips; format/architecture/Detekt pass,4,541 app and library inputs fixed.
[Integration evidence](measurements/pinned-inspector-root-integration-2026-11-09.json). No new production app behavior or
phone update is included. This integration is committed/pushed as57ebc77a; no repeated CI graph is needed.

## Encrypted room binding accepted

This increment includes12 source files: immutable binding entities/repository contract,
exact original/published-room policies, encrypted dual-index storage/codec,14 shared
storage cases with desktop/browser/Apple wrappers and actual application DI resolution.
No owning acquisition, SDK delivery, queue, UI route or deployment changed. Existing
native-owner/provisioning validation remains. [Contract](collaboration-space-room-binding.md).

Formatter40488 and focused82526 are reaped PASS; focused85 cases in183.123s include
14 new SQLite,14 existing metadata,23 room preparation,14 original policy/read and
20 original/room transport cases. The sole following source change resolves the
new repository in the actual application. Full45444 is reaped PASS598.096s:4,187
fresh cases (3,563 desktop,613 Android host,11 browser room-resolution), zero failures/
errors/skips/lint; root/release/Wasm/iOS-source/quality pass,4,544 fixed app/library
inputs. The full runner inherited an old browser filter and did NOT execute the
intended binding classes. That selection failure is preserved, not called a pass.
Corrected browser45197 is reaped PASS33.104s:28 cases,14 binding and14 existing
metadata, zero failures/errors/skips, identical source inputs. No full rerun needed.
[Exact accepted evidence](measurements/collaboration-space-room-binding-2026-11-09.json).
No owned compiler/emulator remains; source freeze is released. Apple execution and
all54 PRD groups remain separate open work. Phone87 remains installed unchanged.

The next owning flow must acquire the exact published original position, match owned
canonical metadata and retain/read back the immutable binding under current admission.
Local facts remain separate from content authority. Preserve all eight originals and
Draft66. All54 PRD acceptance groups and the remaining communication work stay open.

## Owning binding preparation candidate

Encrypted storage0f6c843f is accepted and pushed. The next9 source files are uncommitted:
PrepareCollaborationSpaceRoomBinding, its20-case domain test/fixture, feature DI and
actual app resolution, plus three actual-storage cases with desktop/browser/Apple
wrappers. [Contract](collaboration-space-room-binding-preparation.md).

The local owned read returns exact immutable facts without native expiry/API/SDK work.
Only absence enters finite current-admission acquisition, one room read and, for LEGACY,
one exact original read. The use case matches canonical metadata and reads back uncertain
writes without replacing originals or granting content/SDK authority. No caller is active.

Initial focused71916 is reaped:20 tests PASS, but quality FAIL112.911s because invoke had
105 logical lines (limit100). The owned local read is now a separate operation inside the
same use case; no suppression or generic helper was added. Three real database cases
and platform wrappers followed. Formatter13885 PASS33.980s and43522 PASS34.688s are reaped.
Focused92982 is RUNNING in `.work/space-room-binding-preparation-2026-11-09/focused-2`;
app/Inspector sources are FROZEN until reaped. This is the only heavy local compiler.

The new runner has its own browser profile:20 owning domain cases and three actual
preparation storage cases. Both full/browser selections use that profile without CLI
filters, and the browser-only verifier requires exactly23 cases in the two reports.
Do not reuse the older room-resolution filter. After focused success, run required full
and verify actual browser XML, fixed inputs and lint before accepting/pushing the source.
No source acceptance, new APK, SDK adoption or live deployment is claimed for this candidate.
